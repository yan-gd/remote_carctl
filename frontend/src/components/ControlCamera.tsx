import type { ApiClient } from '../api/client';
import { useCameraStream } from '../camera/useCameraStream';
import { Card, ErrorNotice, StatusPill } from './UI';

export function ControlCamera({ api }: { api: ApiClient }) {
  const { canvas, video, transport, running, frames, fresh, error, toggle } = useCameraStream(api, true);

  return (
    <Card className="control-camera-card" title="车载视角" actions={
      <StatusPill tone={fresh ? 'ok' : 'warn'}>
        {fresh ? '实时画面' : running ? '等待画面' : '未连接'}
      </StatusPill>
    }>
      <div className="camera-stage control-camera-stage">
        {transport === 'webrtc'
          ? <video ref={video} autoPlay muted playsInline aria-label="小车 WebRTC 实时摄像头画面" />
          : <canvas ref={canvas} width={640} height={480} aria-label="小车实时摄像头画面" />}
        {!fresh && <div className="camera-overlay control-camera-overlay">
          <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            strokeWidth="1.2" aria-hidden="true">
            <rect x="3" y="5" width="18" height="14" rx="3" />
            <circle cx="12" cy="12" r="3.5" />
            <path d="M6 8h1" />
          </svg>
          <strong>{running ? '等待摄像头画面' : '画面已断开'}</strong>
          <span>{running ? '收到新帧后自动显示' : '点击连接画面，查看小车前方'}</span>
        </div>}
      </div>
      {error && <ErrorNotice message={error} />}
      <footer className="control-camera-footer">
        <span className="fine-print">{transport === 'webrtc' ? 'H.264 / WebRTC' : 'JPEG 兼容'} · 已接收 {frames} 帧</span>
        <button type="button" className="button ghost small" onClick={toggle}>
          {running ? '断开画面' : '连接画面'}
        </button>
      </footer>
    </Card>
  );
}
