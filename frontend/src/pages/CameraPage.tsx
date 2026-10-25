import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import { useCameraStream } from '../camera/useCameraStream';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';

export function CameraPage({ api }: { api: ApiClient }) {
  const { canvas, video, transport, running, frames, fresh, error: streamError, toggle } = useCameraStream(api);
  const capture = useRef<MediaRecorder | null>(null);
  const recordingTimer = useRef<number | undefined>(undefined);
  const urls = useRef<string[]>([]);
  const mounted = useRef(true);
  const photoRequest = useRef<AbortController | null>(null);
  const [recording, setRecording] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [photo, setPhoto] = useState<string | null>(null);
  const [clip, setClip] = useState<string | null>(null);
  const [clipExtension, setClipExtension] = useState('webm');

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
      let blob: Blob;
      if (transport === 'webrtc') {
        if (!fresh || !video.current || video.current.videoWidth === 0) {
          throw new Error('尚未收到可拍摄的实时画面。');
        }
        const output = document.createElement('canvas');
        output.width = video.current.videoWidth;
        output.height = video.current.videoHeight;
        const context = output.getContext('2d');
        if (!context) throw new Error('浏览器无法生成照片。');
        context.drawImage(video.current, 0, 0);
        blob = await new Promise<Blob>((resolve, reject) => output.toBlob(
          value => value ? resolve(value) : reject(new Error('照片编码失败。')), 'image/jpeg', 0.85));
      } else {
        const response = await api.cameraResponse('snapshot', controller.signal);
        blob = await response.blob();
      }
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
    if (!(transport === 'webrtc' ? video.current : canvas.current) || !fresh) return;
    const mime = ['video/webm;codecs=vp8', 'video/webm', 'video/mp4'].find(type =>
      typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(type));
    if (!mime || (transport === 'jpeg' && !canvas.current?.captureStream)) {
      setError('当前浏览器不支持录制，请使用新版 Chrome 或 Edge。'); return;
    }
    let stream: MediaStream | null = null;
    try {
      if (transport === 'webrtc') {
        const incoming = video.current?.srcObject;
        if (!(incoming instanceof MediaStream)) throw new Error('WebRTC 视频流不可用。');
        stream = new MediaStream(incoming.getVideoTracks().map(track => track.clone()));
      } else {
        stream = canvas.current!.captureStream(8);
      }
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
    {(error || streamError) && <ErrorNotice message={error || streamError!} />}
    <Card title="实时摄像头" actions={<StatusPill tone={fresh ? 'ok' : 'warn'}>
      {fresh ? '实时画面' : running ? '等待新画面' : '未连接'}</StatusPill>}>
      <div className="camera-stage">
        {transport === 'webrtc'
          ? <video ref={video} autoPlay muted playsInline aria-label="小车 WebRTC 实时摄像头画面" />
          : <canvas ref={canvas} width={640} height={480} aria-label="小车实时摄像头画面" />}
        {!fresh && <div className="camera-overlay">{running ? '等待摄像头上传新帧…' : '连接摄像头后查看实时画面'}</div>}
      </div>
      <div className="camera-actions">
        <button className="button primary" onClick={() => {
          if (running) stopRecording();
          setError(null); toggle();
        }}>{running ? '断开画面' : '连接画面'}</button>
        <button className="button" onClick={() => void takePhoto()}>拍照</button>
        <button className="button" disabled={!recording && !fresh} onClick={recording ? stopRecording : record}>
          {recording ? '结束录像' : '录制30秒内视频'}</button>
        <span className="fine-print">{transport === 'webrtc' ? 'H.264 / WebRTC' : 'JPEG 兼容'} · 已接收 {frames} 帧 · 服务端不保存</span>
      </div>
      <p className="fine-print">摄像头画面通过 WebRTC 或 HTTPS/JPEG 实时传输；服务端不写文件、不存数据库、不提供历史录像。
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
