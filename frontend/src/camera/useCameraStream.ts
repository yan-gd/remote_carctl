import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import { ApiError } from '../api/client';
import { CAMERA_TRANSPORT } from '../config';
import { errorMessage } from '../components/UI';
import { readFrames } from './frameStream';
import { watchWhep } from './whep';

/** Authenticated live camera. WebRTC is opt-in; JPEG remains a compatibility fallback. */
export function useCameraStream(api: ApiClient, autoConnect = false) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const [transport, setTransport] = useState<'jpeg' | 'webrtc'>(CAMERA_TRANSPORT);
  const [running, setRunning] = useState(autoConnect);
  const [frames, setFrames] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [lastFrame, setLastFrame] = useState<number | null>(null);
  const [now, setNow] = useState(Date.now());
  const fresh = running && lastFrame !== null && now - lastFrame < 3000;

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!running) return;
    const controller = new AbortController();
    const { signal } = controller;
    let retryTimer: number | undefined;
    let wake: (() => void) | undefined;
    let stopped = false;
    let failures = 0;
    function received() {
      if (signal.aborted) return;
      setFrames(value => value + 1);
      setLastFrame(Date.now());
      setError(null);
    }
    async function jpegStream() {
      // A transport can stay HTTP-open while its camera frames have frozen.
      // Cancel this fetch only (not the whole hook) so the next attempt is fresh.
      const attempt = new AbortController();
      const cancel = () => attempt.abort();
      let lastDecoded = Date.now();
      let timedOut = false;
      signal.addEventListener('abort', cancel, { once: true });
      const watchdog = window.setInterval(() => {
        if (Date.now() - lastDecoded > 7000) {
          timedOut = true;
          attempt.abort();
        }
      }, 1000);
      try {
        const response = await api.cameraResponse('stream', attempt.signal);
        if (!response.body) throw new Error('浏览器不支持流式视频。');
        await readFrames(response.body, async jpeg => {
          const bitmap = await createImageBitmap(new Blob([jpeg as BlobPart], { type: 'image/jpeg' }));
          try {
            if (attempt.signal.aborted) return;
            const target = canvas.current;
            if (!target) return;
            const context = target.getContext('2d');
            if (!context) return;
            const scale = Math.min(target.width / bitmap.width, target.height / bitmap.height);
            context.fillStyle = '#07111b';
            context.fillRect(0, 0, target.width, target.height);
            context.drawImage(bitmap, (target.width - bitmap.width * scale) / 2,
              (target.height - bitmap.height * scale) / 2, bitmap.width * scale, bitmap.height * scale);
            lastDecoded = Date.now();
            received();
          } finally { bitmap.close(); }
        }, attempt.signal);
        if (timedOut) throw new Error('JPEG 画面超过 7 秒未更新');
        // A clean 55-second server rotation is not a failure.
      } catch (error) {
        if (timedOut && !signal.aborted) throw new Error('JPEG 画面超过 7 秒未更新');
        throw error;
      } finally {
        window.clearInterval(watchdog);
        signal.removeEventListener('abort', cancel);
        attempt.abort();
      }
    }
    async function pause(ms: number) {
      await new Promise<void>(resolve => {
        wake = resolve;
        retryTimer = window.setTimeout(resolve, ms);
        if (signal.aborted) resolve();
      });
      wake = undefined;
    }
    async function watch() {
      while (!signal.aborted) {
        try {
          if (transport === 'webrtc') {
            if (!video.current) throw new Error('视频播放器尚未就绪。');
            await watchWhep(api, video.current, signal, received);
          } else {
            await jpegStream();
          }
          failures = 0;
          if (!signal.aborted) await pause(300);
        } catch (caught) {
          if (stopped || signal.aborted) break;
          if (caught instanceof ApiError && caught.unauthorized) {
            setRunning(false);
            break;
          }
          failures += 1;
          setLastFrame(null);
          setError(`视频连接中断，正在自动重连：${errorMessage(caught)}`);
          if (transport === 'webrtc' && failures >= 3) {
            setError('WebRTC 暂不可用，已切换至 JPEG 兼容画面。');
            setTransport('jpeg');
            break;
          }
          await pause(Math.min(1000 * 2 ** (failures - 1), 8000));
        }
      }
    }
    void watch();
    return () => {
      stopped = true;
      controller.abort();
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
      wake?.();
    };
  }, [api, running, transport]);

  function toggle() {
    setError(null);
    setLastFrame(null);
    if (!running) setTransport(CAMERA_TRANSPORT);
    setRunning(value => !value);
  }

  return { canvas, video, transport, running, frames, fresh, error, toggle };
}
