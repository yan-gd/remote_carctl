import type { ApiClient } from '../api/client';

const WHEP_PATH = '/webrtc/SCOUT-001/whep';

function aborted(): DOMException {
  return new DOMException('视频连接已取消', 'AbortError');
}

/** Non-trickle WHEP: gather ICE candidates before sending the SDP offer. */
async function gatherIce(peer: RTCPeerConnection, signal: AbortSignal): Promise<void> {
  if (peer.iceGatheringState === 'complete') return;
  await new Promise<void>((resolve, reject) => {
    const timer = window.setTimeout(() => finish(new Error('ICE 候选收集超时')), 6000);
    function finish(error?: Error) {
      window.clearTimeout(timer);
      peer.removeEventListener('icegatheringstatechange', changed);
      signal.removeEventListener('abort', cancelled);
      if (error) reject(error); else resolve();
    }
    function changed() { if (peer.iceGatheringState === 'complete') finish(); }
    function cancelled() { finish(aborted()); }
    peer.addEventListener('icegatheringstatechange', changed);
    signal.addEventListener('abort', cancelled, { once: true });
    if (signal.aborted) cancelled();
    else changed();
  });
}

/** Only accept a relative session URL belonging to the same protected WHEP endpoint. */
export function whepSessionPath(location: string | null, origin: string): string | null {
  if (!location) return null;
  const url = new URL(location, origin);
  if (url.origin !== origin || url.search || url.hash ||
      !/^\/webrtc\/SCOUT-001\/whep\/[A-Za-z0-9_-]+$/.test(url.pathname)) {
    throw new Error('视频服务器返回了不安全的会话地址。');
  }
  return url.pathname;
}

/** Refreshes a healthy session after 55s to recheck auth; never logs SDP/tokens/ICE. */
export async function watchWhep(
  api: ApiClient,
  video: HTMLVideoElement,
  signal: AbortSignal,
  onFrame: () => void,
): Promise<void> {
  if (!window.RTCPeerConnection) throw new Error('浏览器不支持 WebRTC。');
  if (signal.aborted) throw aborted();
  const peer = new RTCPeerConnection();
  let session: string | null = null;
  let frameId: number | undefined;
  let sampleTimer: number | undefined;
  let lastDecoded = Date.now();
  let lastPlaybackTime = -1;
  try {
    peer.addTransceiver('video', { direction: 'recvonly' });
    peer.ontrack = event => {
      if (event.track.kind !== 'video') return;
      video.srcObject = event.streams[0] ?? new MediaStream([event.track]);
      void video.play().catch(() => undefined);
      if (video.requestVideoFrameCallback) {
        const sample = () => {
          if (signal.aborted) return;
          lastDecoded = Date.now();
          onFrame();
          frameId = video.requestVideoFrameCallback(sample);
        };
        frameId = video.requestVideoFrameCallback(sample);
      } else {
        sampleTimer = window.setInterval(() => {
          if (video.readyState >= 2 && video.currentTime !== lastPlaybackTime) {
            lastPlaybackTime = video.currentTime;
            lastDecoded = Date.now();
            onFrame();
          }
        }, 250);
      }
    };

    await peer.setLocalDescription(await peer.createOffer());
    await gatherIce(peer, signal);
    const offer = peer.localDescription?.sdp;
    if (!offer || signal.aborted) throw aborted();
    const response = await api.whepRequest('POST', WHEP_PATH, signal, offer);
    session = whepSessionPath(response.headers.get('Location'), window.location.origin);
    const answer = await response.text();
    if (!answer.startsWith('v=0')) throw new Error('视频服务器未返回有效 SDP。');
    await peer.setRemoteDescription({ type: 'answer', sdp: answer });

    await new Promise<void>((resolve, reject) => {
      let finished = false;
      const timer = window.setTimeout(() => finish(new Error('WebRTC 连接超时')), 12000);
      // Unlike the 55-second JPEG chunks, an ICE session would otherwise outlive
      // the JWT indefinitely. Re-open it through nginx auth_request each minute.
      const refresh = window.setTimeout(() => finish(), 55000);
      const stall = window.setInterval(() => {
        if (peer.connectionState === 'connected' && Date.now() - lastDecoded > 6000) {
          finish(new Error('WebRTC 画面停止更新'));
        }
      }, 1000);
      function finish(error?: Error) {
        if (finished) return;
        finished = true;
        window.clearTimeout(timer);
        window.clearTimeout(refresh);
        window.clearInterval(stall);
        peer.removeEventListener('connectionstatechange', changed);
        signal.removeEventListener('abort', cancelled);
        if (error) reject(error); else resolve();
      }
      function changed() {
        if (peer.connectionState === 'connected') {
          window.clearTimeout(timer);
          lastDecoded = Date.now();
        }
        if (peer.connectionState === 'failed' || peer.connectionState === 'closed' ||
            peer.connectionState === 'disconnected') {
          finish(new Error('WebRTC 连接中断'));
        }
      }
      function cancelled() { finish(aborted()); }
      peer.addEventListener('connectionstatechange', changed);
      signal.addEventListener('abort', cancelled, { once: true });
      if (signal.aborted) cancelled(); else changed();
    });
  } finally {
    if (frameId !== undefined) video.cancelVideoFrameCallback?.(frameId);
    if (sampleTimer !== undefined) window.clearInterval(sampleTimer);
    video.pause();
    video.srcObject = null;
    peer.close();
    if (session) {
      // Best-effort server cleanup; an aborted viewer must not leak an active session.
      void api.whepRequest('DELETE', session, new AbortController().signal).catch(() => undefined);
    }
  }
}
