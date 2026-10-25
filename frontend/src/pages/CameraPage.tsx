import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import { readFrames } from '../camera/frameStream';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';

export function CameraPage({ api }: { api: ApiClient }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const capture = useRef<MediaRecorder | null>(null);
  const recordingTimer = useRef<number | undefined>(undefined);
  const urls = useRef<string[]>([]);
  const mounted = useRef(true);
  const photoRequest = useRef<AbortController | null>(null);
  const [running, setRunning] = useState(false);
  const [recording, setRecording] = useState(false);
  const [frames, setFrames] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [photo, setPhoto] = useState<string | null>(null);
  const [clip, setClip] = useState<string | null>(null);
  const [clipExtension, setClipExtension] = useState('webm');
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
              // Fixed output dimensions keep MediaRecorder's track stable.
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

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      photoRequest.current?.abort();
      window.clearTimeout(recordingTimer.current);
      if (capture.current?.state === 'recording') capture.current.stop();
      for (const url of urls.current) URL.revokeObjectURL(url);
      urls.current = [];
    };
  }, []);

  useEffect(() => {
    // Do not encode a frozen last image as live video after a camera outage.
    if (!fresh && capture.current?.state === 'recording') stopRecording();
  }, [fresh]);

  function blobUrl(blob: Blob) {
    const url = URL.createObjectURL(blob);
    urls.current.push(url);
    return url;
  }

  async function takePhoto() {
    setError(null);
    photoRequest.current?.abort();
    const controller = new AbortController();
    photoRequest.current = controller;
    const timer = window.setTimeout(() => controller.abort(), 7000);
    try {
      const response = await api.cameraResponse('snapshot', controller.signal);
      const blob = await response.blob();
      if (!mounted.current || controller.signal.aborted) return;
      if (photo) URL.revokeObjectURL(photo);
      setPhoto(blobUrl(blob));
    } catch (caught) { if (mounted.current && !controller.signal.aborted) setError(errorMessage(caught)); }
    finally { window.clearTimeout(timer); }
  }

  function stopRecording() {
    window.clearTimeout(recordingTimer.current);
    if (capture.current?.state === 'recording') capture.current.stop();
  }

  function record() {
    setError(null);
    if (!canvas.current || !fresh) return;
    const mime = ['video/webm;codecs=vp8', 'video/webm', 'video/mp4'].find(type =>
      typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(type));
    if (!mime || !canvas.current.captureStream) {
      setError('当前浏览器不支持录制，请使用新版 Chrome 或 Edge。'); return;
    }
    let stream: MediaStream | null = null;
    try {
      stream = canvas.current.captureStream(8);
      const tracks = stream.getTracks();
      const recorder = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 700_000 });
      const chunks: Blob[] = [];
      let bytes = 0;
      recorder.ondataavailable = event => {
        bytes += event.data.size;
        if (bytes > 8 * 1024 * 1024) {
          stopRecording(); if (mounted.current) setError('录像达到8MiB内存限制，已停止。'); return;
        }
        if (event.data.size) chunks.push(event.data);
      };
      recorder.onstop = () => {
        window.clearTimeout(recordingTimer.current);
        tracks.forEach(track => track.stop());
        const blob = new Blob(chunks, { type: mime });
        chunks.length = 0;
        if (!mounted.current) return;
        if (clip) URL.revokeObjectURL(clip);
        setClip(blobUrl(blob));
        setClipExtension(mime.startsWith('video/mp4') ? 'mp4' : 'webm');
        setRecording(false);
      };
      recorder.onerror = () => { if (mounted.current) setError('录像编码失败。'); stopRecording(); };
      capture.current = recorder;
      recorder.start(500);
      setRecording(true);
      recordingTimer.current = window.setTimeout(stopRecording, 30_000);
    } catch (caught) { stream?.getTracks().forEach(track => track.stop()); setError(errorMessage(caught)); }
  }

  return <div className={`stack camera-page${photo || clip ? ' has-captures' : ''}`}>
    {error && <ErrorNotice message={error} />}
    <Card title="实时摄像头" actions={<StatusPill tone={fresh ? 'ok' : 'warn'}>
      {fresh ? '实时画面' : running ? '等待新画面' : '未连接'}</StatusPill>}>
      <div className="camera-stage">
        <canvas ref={canvas} width={640} height={480} aria-label="小车实时摄像头画面" />
        {!fresh && <div className="camera-overlay">{running ? '等待摄像头上传新帧…' : '连接摄像头后查看实时画面'}</div>}
      </div>
      <div className="camera-actions">
        <button className="button primary" onClick={() => {
          if (running) stopRecording();
          setError(null); setRunning(value => !value); setLastFrame(null);
        }}>{running ? '断开画面' : '连接画面'}</button>
        <button className="button" onClick={() => void takePhoto()}>拍照</button>
        <button className="button" disabled={!recording && !fresh} onClick={recording ? stopRecording : record}>
          {recording ? '结束录像' : '录制30秒内视频'}</button>
        <span className="fine-print">已接收 {frames} 帧 · 服务端不保存</span>
      </div>
      <p className="fine-print">摄像头视频帧经 HTTPS 上传后实时转发；服务端不写文件、不存数据库、不提供历史录像。
        拍照和录像仅在当前浏览器内存中预览，需保留时下载到你的电脑。离开页面即释放。</p>
    </Card>
    {(photo || clip) && <Card title="本次拍摄 · 仅当前浏览器">
      <div className="camera-captures">
        {photo && <figure><img src={photo} alt="刚拍摄的实时画面" /><figcaption><a href={photo} download="scout-photo.jpg">下载照片</a></figcaption></figure>}
        {clip && <figure><video src={clip} controls /><figcaption><a href={clip} download={`scout-video.${clipExtension}`}>下载视频</a></figcaption></figure>}
      </div>
    </Card>}
  </div>;
}
