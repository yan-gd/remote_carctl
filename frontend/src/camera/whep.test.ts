import { afterEach, describe, expect, it, vi } from 'vitest';
import { watchWhep, whepSessionPath } from './whep';
import { ApiClient } from '../api/client';

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('WHEP signaling security', () => {
  it('accepts only same-origin protected session paths', () => {
    const origin = 'https://agilex.yoyoyan.cn';
    expect(whepSessionPath('/webrtc/SCOUT-001/whep/session-01', origin))
      .toBe('/webrtc/SCOUT-001/whep/session-01');
    expect(whepSessionPath(null, origin)).toBeNull();
    expect(() => whepSessionPath('https://attacker.test/webrtc/SCOUT-001/whep/a', origin)).toThrow('不安全');
    expect(() => whepSessionPath('/webrtc/OTHER/whep/session-01', origin)).toThrow('不安全');
    expect(() => whepSessionPath('/webrtc/SCOUT-001/whep/a?token=secret', origin)).toThrow('不安全');
  });

  it('sends the in-memory JWT in a header, never in the WHEP URL', async () => {
    const fetcher = vi.fn(async () => new Response('v=0\r\n', {
      status: 201,
      headers: { Location: '/webrtc/SCOUT-001/whep/session-01' },
    })) as unknown as typeof fetch;
    const api = new ApiClient(fetcher);
    api.setToken('memory-only-token');
    const response = await api.whepRequest('POST', '/webrtc/SCOUT-001/whep', new AbortController().signal, 'v=0\r\n');
    expect(response.status).toBe(201);
    const [url, options] = vi.mocked(fetcher).mock.calls[0];
    expect(url).toBe('/webrtc/SCOUT-001/whep');
    expect(options?.headers).toMatchObject({ Authorization: 'Bearer memory-only-token' });
    await expect(api.whepRequest('DELETE', '/webrtc/SCOUT-001/whep/../../api', new AbortController().signal))
      .rejects.toThrow();
  });

  it('closes and renews a healthy stream after 55 seconds to recheck JWT', async () => {
    vi.useFakeTimers();
    const close = vi.fn();
    class Peer extends EventTarget {
      iceGatheringState = 'complete';
      connectionState = 'new';
      localDescription = { sdp: 'v=0\r\n' };
      ontrack: ((event: RTCTrackEvent) => void) | null = null;
      addTransceiver() {}
      async createOffer() { return { type: 'offer', sdp: 'v=0\r\n' }; }
      async setLocalDescription() {}
      async setRemoteDescription() {
        this.connectionState = 'connected';
        this.ontrack?.({ track: { kind: 'video' }, streams: [{}] } as unknown as RTCTrackEvent);
        this.dispatchEvent(new Event('connectionstatechange'));
      }
      close() { close(); }
    }
    vi.stubGlobal('RTCPeerConnection', Peer);
    const request = vi.fn(async (method: string) => method === 'POST'
      ? new Response('v=0\r\n', { status: 201, headers: { Location: '/webrtc/SCOUT-001/whep/renew-01' } })
      : new Response(null, { status: 200 }));
    const api = { whepRequest: request } as unknown as ApiClient;
    let callback: VideoFrameRequestCallback | undefined;
    const pause = vi.fn();
    const video = {
      srcObject: null, pause, play: vi.fn(async () => {}),
      requestVideoFrameCallback: vi.fn((next: VideoFrameRequestCallback) => { callback = next; return 1; }),
      cancelVideoFrameCallback: vi.fn(),
    } as unknown as HTMLVideoElement;
    const frames = vi.fn();
    const sample = window.setInterval(() => callback?.(0, {} as VideoFrameCallbackMetadata), 1000);
    const watching = watchWhep(api, video, new AbortController().signal, frames);
    await vi.advanceTimersByTimeAsync(1000);
    expect(request).toHaveBeenCalledWith('POST', '/webrtc/SCOUT-001/whep', expect.any(AbortSignal), 'v=0\r\n');
    await vi.advanceTimersByTimeAsync(55000);
    await watching;
    window.clearInterval(sample);
    expect(frames).toHaveBeenCalled();
    expect(close).toHaveBeenCalledOnce();
    expect(pause).toHaveBeenCalledOnce();
    expect(request).toHaveBeenCalledWith('DELETE', '/webrtc/SCOUT-001/whep/renew-01', expect.any(AbortSignal));
  });
});
