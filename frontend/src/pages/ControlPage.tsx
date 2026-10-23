import { useEffect, useState } from 'react';
import type { ApiClient } from '../api/client';
import { useHoldControl } from '../control/useHoldControl';
import { useRobot } from '../robot/RobotContext';
import { formatNumber, motionLabel } from '../utils/format';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';

const DIRECTIONS: Array<{ command: string; label: string; symbol: string }> = [
  { command: 'FORWARD', label: '前进', symbol: '↑' },
  { command: 'LEFT', label: '左转', symbol: '←' },
  { command: 'STOP', label: '停止', symbol: '■' },
  { command: 'RIGHT', label: '右转', symbol: '→' },
  { command: 'BACKWARD', label: '后退', symbol: '↓' },
];

export function ControlPage({ api, role }: { api: ApiClient; role: string }) {
  const { status, error: statusError } = useRobot();
  const [controlError, setControlError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pressed, setPressed] = useState<string | null>(null);
  const controller = useHoldControl(api, (caught) => {
    setPressed(null);
    setControlError(errorMessage(caught));
  });
  const canMove = role === 'OPERATOR';
  const canStop = role === 'OPERATOR' || role === 'MONITOR';

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
    <div className="stack">
      {statusError && <ErrorNotice message={statusError} />}
      {controlError && <ErrorNotice message={controlError} />}
      {notice && <div className="notice notice-info">{notice}</div>}

      <Card
        className="control-card"
        title="运动控制"
        actions={
          <StatusPill tone={status?.online ? 'ok' : 'warn'}>
            {status?.online ? '在线' : '离线'}
          </StatusPill>
        }
      >
        <div className="control-layout">
          <div className="control-copy">
            <p className="control-lead">
              控制请求串行发送。松开、失焦或退出时，STOP 会等待在途指令结束后再发出。
            </p>

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

            <ul className="plain-list control-safety">
              <li>确认现场无人、车轮架空或场地安全后再试车。</li>
              <li>网络中断时依赖小车侧 0.6 s 看门狗停车。</li>
              <li>紧急情况立即点击红色 STOP。</li>
            </ul>

            {!canMove && (
              <p className="notice notice-info">
                {canStop ? '当前角色只能急停，不能驱动。' : '当前角色为只读，不能控制机器人。'}
              </p>
            )}
          </div>

          <div className="control-stage">
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
        </div>
      </Card>
    </div>
  );
}
