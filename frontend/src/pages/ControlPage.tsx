import { useEffect, useState } from 'react';
import type { ApiClient } from '../api/client';
import { useHoldControl } from '../control/useHoldControl';
import { useRobot } from '../robot/RobotContext';
import { formatNumber, motionLabel } from '../utils/format';
import { Card, ErrorNotice, StatusPill, errorMessage } from '../components/UI';

const DIRECTIONS: Array<{ command: string; label: string; symbol: string }> = [
  { command: 'FORWARD', label: '前进', symbol: '↑' },
  { command: 'LEFT', label: '左转', symbol: '↰' },
  { command: 'STOP', label: '停止', symbol: '■' },
  { command: 'RIGHT', label: '右转', symbol: '↱' },
  { command: 'BACKWARD', label: '后退', symbol: '↓' },
];

export function ControlPage({ api, role }: { api: ApiClient; role: string }) {
  const { status, error: statusError } = useRobot();
  const [controlError, setControlError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const controller = useHoldControl(api, (caught) => setControlError(errorMessage(caught)));
  const canMove = role === 'OPERATOR';
  const canStop = role === 'OPERATOR' || role === 'MONITOR';

  useEffect(() => {
    const stop = () => {
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

  async function stop() {
    setControlError(null);
    const ok = await controller.stop(true);
    setNotice(ok ? '已发送 STOP。' : 'STOP 发送失败，请重试或使用急停。');
  }

  function begin(command: string) {
    if (!canMove) return;
    setControlError(null);
    setNotice(null);
    void controller.start(command);
  }

  function end() {
    if (!canMove) return;
    void controller.stop();
  }

  return (
    <div className="stack">
      {statusError && <ErrorNotice message={statusError} />}
      {controlError && <ErrorNotice message={controlError} />}
      {notice && <div className="notice notice-info">{notice}</div>}

      <Card
        title="运动控制"
        actions={
          <StatusPill tone={status?.online ? 'ok' : 'warn'}>
            {status?.online ? '在线' : '离线'}
          </StatusPill>
        }
      >
        <p className="muted">
          按住方向键持续发送，松开自动 STOP。切换页面、浏览器失焦或退出登录也会 STOP。
        </p>

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
                >
                  <span>{item.symbol}</span>
                  {item.label}
                </button>
              );
            }
            return (
              <button
                key={item.command}
                type="button"
                className={`control-button ${item.command.toLowerCase()}`}
                disabled={!canMove || !status?.online}
                onPointerDown={(event) => {
                  event.currentTarget.setPointerCapture?.(event.pointerId);
                  begin(item.command);
                }}
                onPointerUp={end}
                onPointerCancel={end}
                onPointerLeave={end}
                onContextMenu={(event) => event.preventDefault()}
              >
                <span>{item.symbol}</span>
                {item.label}
              </button>
            );
          })}
        </div>

        {!canMove && (
          <p className="fine-print">
            {canStop ? '当前角色只能急停，不能驱动。' : '当前角色为只读，不能控制机器人。'}
          </p>
        )}
      </Card>

      <div className="grid two">
        <Card title="当前状态">
          <dl className="key-values">
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
        </Card>
        <Card title="安全提示">
          <ul className="plain-list">
            <li>确认现场无人、车轮架空或场地安全后再试车。</li>
            <li>网络中断时依赖小车侧 0.6 s 看门狗停车。</li>
            <li>紧急情况立即点击红色 STOP。</li>
          </ul>
        </Card>
      </div>
    </div>
  );
}
