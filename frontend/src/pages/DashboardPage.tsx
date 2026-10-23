import { useRobot } from '../robot/RobotContext';
import { formatDateTime, formatNumber, formatRelative, motionLabel } from '../utils/format';
import { Card, Empty, ErrorNotice, StatusPill } from '../components/UI';

export function DashboardPage() {
  const { info, status, error, updatedAt, refresh } = useRobot();

  if (!status && !info && !error) {
    return <Empty>正在读取机器人状态…</Empty>;
  }

  return (
    <div className="stack">
      {error && <ErrorNotice message={error} onRetry={() => void refresh()} />}

      <Card className="hero-card">
        <div className="hero-main">
          <p className="kicker">{info?.model ?? 'SCOUT MINI'} · LIVE TELEMETRY</p>
          <h2 className="hero-title">{info?.name ?? '机器人'}</h2>
          <p className="hero-meta">
            {info?.robotId ?? status?.robotId ?? '未知编号'}
            <span aria-hidden="true">·</span>
            {updatedAt ? `最近更新 ${formatRelative(updatedAt.toISOString())}` : '等待数据'}
          </p>
        </div>

        <div className="hero-side">
          <div className="pill-row">
            <StatusPill tone={status?.online ? 'ok' : 'warn'}>
              {status?.online ? '在线' : '离线'}
            </StatusPill>
            <StatusPill tone={status?.mqttConnected ? 'ok' : 'bad'}>
              MQTT {status?.mqttConnected ? '已连接' : '未连接'}
            </StatusPill>
            <StatusPill
              tone={
                status?.chassisConnected === null || status?.chassisConnected === undefined
                  ? 'neutral'
                  : status.chassisConnected
                    ? 'ok'
                    : 'bad'
              }
            >
              底盘{' '}
              {status?.chassisConnected === null || status?.chassisConnected === undefined
                ? '未知'
                : status.chassisConnected
                  ? '已连接'
                  : '未连接'}
            </StatusPill>
          </div>

          <div className="hero-actions">
            <span className="fine-print">每 1 秒同步</span>
            <button
              type="button"
              className="button ghost small"
              onClick={() => void refresh()}
            >
              立即刷新
            </button>
          </div>
        </div>
      </Card>

      <section className="section">
        <header className="section-head">
          <h2 className="section-title">实时遥测</h2>
          <span className="section-note">底盘 /scout_status · 数据新鲜时展示</span>
        </header>

        <div className="grid metrics">
          <Metric label="运动状态" value={motionLabel(status?.motionState ?? 'UNKNOWN')} />
          <Metric
            label="电池电压"
            value={formatNumber(status?.batteryVoltage, 2)}
            unit="V"
            hint="来自底盘 /scout_status"
          />
          <Metric
            label="线速度"
            value={formatNumber(status?.linearVelocity, 2)}
            unit="m/s"
            hint="底盘实测值"
          />
          <Metric
            label="角速度"
            value={formatNumber(status?.angularVelocity, 2)}
            unit="rad/s"
            hint="底盘实测值"
          />
          <Metric
            label="里程计 X"
            value={formatNumber(status?.position?.x, 2)}
            unit="m"
            hint="轮式里程计，不是全局定位"
          />
          <Metric
            label="里程计 Y"
            value={formatNumber(status?.position?.y, 2)}
            unit="m"
            hint="轮式里程计，不是全局定位"
          />
          <Metric
            label="航向角"
            value={formatNumber(status?.position?.yaw, 2)}
            unit="rad"
            hint="轮式里程计，会漂移"
          />
          <Metric
            label="最近心跳"
            value={formatRelative(status?.lastSeenAt)}
            hint={formatDateTime(status?.lastSeenAt)}
          />
        </div>
      </section>

      <Card className="ack-card" title="最近执行回执">
        {status?.lastAck ? (
          <div className="ack">
            <div className="ack-main">
              <StatusPill tone={status.lastAck.result === 'APPLIED' ? 'ok' : 'bad'}>
                {status.lastAck.result}
              </StatusPill>
              <strong className="ack-command">{status.lastAck.command}</strong>
              <span className="ack-seq">seq {status.lastAck.seq}</span>
            </div>
            <div className="ack-meta">
              <span>{status.lastAck.reason ?? '无附加原因'}</span>
              <span>{formatDateTime(status.lastAck.receivedAt)}</span>
            </div>
          </div>
        ) : (
          <Empty>还没有控制回执。</Empty>
        )}
        <p className="fine-print">
          APPLIED 表示桥接已写入 /cmd_vel，不代表底盘物理动作。遥测仅在数据新鲜时展示，
          null 不补值。
        </p>
      </Card>
    </div>
  );
}

function Metric({
  label,
  value,
  unit,
  hint,
}: {
  label: string;
  value: string;
  unit?: string;
  hint?: string;
}) {
  return (
    <div className="metric">
      <span className="metric-label">{label}</span>
      <strong className="metric-value">
        {value}
        {unit && value !== '—' && <span className="metric-unit">{unit}</span>}
      </strong>
      {hint && <span className="metric-hint">{hint}</span>}
    </div>
  );
}
