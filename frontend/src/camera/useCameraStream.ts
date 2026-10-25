import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import { errorMessage } from '../components/UI';
import { readFrames } from './frameStream';

/** One authenticated stream per mounted view; release it when leaving the page. */
export function useCameraStream(api: ApiClient, autoConnect = false) {
  const canvas = useRef<HTMLCanvasElement>(null);
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
    let stopped = false;
    async function watch() {
      try {
        while (!controller.signal.aborted) {
          const response = await api.cameraResponse('stream', controller.signal);
          if (!response.body) throw new Error('浏览器不支持流式视频。');
          await readFrames(response.body, async jpeg => {
            const bitmap = await createImageBitmap(new Blob([jpeg as BlobPart], { type: 'image/jpeg' }));
            try {
              if (controller.signal.aborted) return;
              const target = canvas.current;
              if (!target) return;
              // Fixed output dimensions also keep the camera page recorder stable.
              const context = target.getContext('2d');
              if (!context) return;
              const scale = Math.min(target.width / bitmap.width, target.height / bitmap.height);
              context.fillStyle = '#07111b';
              context.fillRect(0, 0, target.width, target.height);
              context.drawImage(bitmap, (target.width - bitmap.width * scale) / 2,
                (target.height - bitmap.height * scale) / 2, bitmap.width * scale, bitmap.height * scale);
              setFrames(value => value + 1);
              setLastFrame(Date.now());
            } finally { bitmap.close(); }
          }, controller.signal);
          if (!controller.signal.aborted) await new Promise(resolve => window.setTimeout(resolve, 300));
        }
      } catch (caught) {
        if (!stopped) { setError(errorMessage(caught)); setRunning(false); }
      }
    }
    void watch();
    return () => { stopped = true; controller.abort(); };
  }, [api, running]);

  function toggle() {
    setError(null);
    setLastFrame(null);
    setRunning(value => !value);
  }

  return { canvas, running, frames, fresh, error, toggle };
}
