import { useEffect, useState } from 'react';
import type { ApiClient } from '../api/client';
import { useHoldControl } from '../control/useHoldControl';
import { useRobot } from '../robot/RobotContext';
import { formatNumber, motionLabel } from '../utils/format';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';
import { ControlCamera } from '../components/ControlCamera';
import { permissions } from '../auth/permissions';

const DIRECTIONS: Array<{ command: string; label: string; symbol: string }> = [
  { command: 'FORWARD', label: '前进', symbol: '↑' },
  { command: 'LEFT', label: '左转', symbol: '←' },
  { command: 'STOP', label: '停止', symbol: '■' },
  { command: 'RIGHT', label: '右转', symbol: '→' },
  { command: 'BACKWARD', label: '后退', symbol: '↓' },
];

export function ControlPage({ api, role, grants }: { api: ApiClient; role: string; grants?: string[] }) {
  const { status, error: statusError } = useRobot();
  const [controlError, setControlError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pressed, setPressed] = useState<string | null>(null);
  const controller = useHoldControl(api, (caught) => {
    setPressed(null);
    setControlError(errorMessage(caught));
  });
  const {move:canMove,stop:canStop} = permissions(role,grants);

  useEffect(() => {
    const stop = () => {
      setPressed(null);
      void controller.stop();
    };
    const onVisibility = () => {
      if (document.hidden) stop();
    };
    window.addEventListener('blur', stop);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('blur', stop);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [controller]);

  useEffect(() => {
    if (!status?.online) setPressed(null);
  }, [status?.online]);

  async function stop() {
    setPressed(null);
    setControlError(null);
    const ok = await controller.stop(true);
    setNotice(ok ? '已发送 STOP。' : 'STOP 发送失败，请重试或使用急停。');
  }

  function begin(command: string) {
    if (!canMove) return;
    setControlError(null);
    setNotice(null);
    setPressed(command);
    void controller.start(command);
  }

  function end() {
    if (!canMove) return;
    setPressed(null);
    void controller.stop();
  }

  return (
    <div className="stack control-page">
      {statusError && <ErrorNotice message={statusError} />}
      {controlError && <ErrorNotice message={controlError} />}
      {notice && <div className="notice notice-info">{notice}</div>}

      <div className="control-console">
        <ControlCamera api={api} />
        <Card
          className="control-card"
          title="运动控制"
          actions={
            <StatusPill tone={status?.online ? 'ok' : 'warn'}>
              {status?.online ? '在线' : '离线'}
            </StatusPill>
          }
        >
          <dl className="control-readout">
            <div>
              <dt>运动状态</dt>
              <dd>{motionLabel(status?.motionState ?? 'UNKNOWN')}</dd>
            </div>
            <div>
              <dt>线速度</dt>
              <dd>{formatNumber(status?.linearVelocity, 2, 'm/s')}</dd>
            </div>
            <div>
              <dt>角速度</dt>
              <dd>{formatNumber(status?.angularVelocity, 2, 'rad/s')}</dd>
            </div>
            <div>
              <dt>电池电压</dt>
              <dd>{formatNumber(status?.batteryVoltage, 2, 'V')}</dd>
            </div>
          </dl>

          <div className="control-stage">
            <p className="control-hint">按住方向行驶 · 松开停止</p>
            <div className="control-grid">
              {DIRECTIONS.map((item) => {
                if (item.command === 'STOP') {
                  return (
                    <button
                      key={item.command}
                      type="button"
                      className="control-button stop"
                      disabled={!canStop}
                      onClick={() => void stop()}
                      title="立即停车"
                      aria-label="停止"
                    >
                      <span className="control-symbol" aria-hidden="true">
                        {item.symbol}
                      </span>
                      <span className="control-label">{item.label}</span>
                    </button>
                  );
                }
                return (
                  <button
                    key={item.command}
                    type="button"
                    className={`control-button ${item.command.toLowerCase()} ${
                      pressed === item.command ? 'is-pressed' : ''
                    }`.trim()}
                    disabled={!canMove || !status?.online}
                    onPointerDown={(event) => {
                      event.currentTarget.setPointerCapture?.(event.pointerId);
                      begin(item.command);
                    }}
                    onPointerUp={end}
                    onPointerCancel={end}
                    onPointerLeave={end}
                    onContextMenu={(event) => event.preventDefault()}
                    aria-label={item.label}
                  >
                    <span className="control-symbol" aria-hidden="true">
                      {item.symbol}
                    </span>
                    <span className="control-label">{item.label}</span>
                  </button>
                );
              })}
            </div>
          </div>
          <div className="control-footnote">
            {!canMove && (
              <p className="notice notice-info">
                {canStop ? '当前角色只能急停，不能驱动。' : '当前角色为只读，不能控制机器人。'}
              </p>
            )}
            <p>失焦或离开页面会自动发送停止指令。</p>
            <p>确认现场安全后操作；紧急情况点击停止。</p>
          </div>
        </Card>
      </div>
    </div>
  );
}
