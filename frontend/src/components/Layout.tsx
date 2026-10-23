import { useState, type ReactNode } from 'react';
import { useRobot } from '../robot/RobotContext';
import { formatRelative, roleLabel } from '../utils/format';
import { StatusPill } from './UI';

export type Tab = 'dashboard' | 'inspection' | 'control' | 'alarms' | 'logs';

const NAV: Array<{ id: Tab; label: string; hint: string }> = [
  { id: 'dashboard', label: '总览', hint: '机器人状态' },
  { id: 'inspection', label: '巡检', hint: '路线与任务' },
  { id: 'control', label: '控制', hint: '按住行驶' },
  { id: 'alarms', label: '告警', hint: '告警处理' },
  { id: 'logs', label: '日志', hint: '操作记录' },
];

export function Shell({
  username,
  role,
  active,
  onNavigate,
  onLogout,
  children,
}: {
  username: string;
  role: string;
  active: Tab;
  onNavigate: (tab: Tab) => void;
  onLogout: () => Promise<void>;
  children: ReactNode;
}) {
  const { status, info, error } = useRobot();
  const [loggingOut, setLoggingOut] = useState(false);
  const online = status?.online ?? info?.online ?? false;
  const current = NAV.find((item) => item.id === active);

  async function logout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await onLogout();
    } finally {
      setLoggingOut(false);
    }
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark small" aria-hidden="true">
            S
          </div>
          <div>
            <strong>SCOUT</strong>
            <span>巡检控制台</span>
          </div>
        </div>

        <nav className="nav" aria-label="主导航">
          {NAV.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`nav-item ${active === item.id ? 'active' : ''}`}
              onClick={() => onNavigate(item.id)}
              aria-current={active === item.id ? 'page' : undefined}
              title={item.hint}
            >
              <span className="nav-dot" aria-hidden="true" />
              {item.label}
            </button>
          ))}
        </nav>

        <div className="sidebar-foot">
          <div className="connection">
            <StatusPill tone={online ? 'ok' : 'warn'}>
              {online ? '机器人在线' : '机器人离线'}
            </StatusPill>
            <span className="muted small">
              {status?.lastSeenAt ? `心跳 ${formatRelative(status.lastSeenAt)}` : '等待心跳'}
            </span>
          </div>
          <div className="user-box">
            <span className="avatar" aria-hidden="true">
              {username.slice(0, 1).toUpperCase()}
            </span>
            <div className="user-meta">
              <strong>{username}</strong>
              <span>{roleLabel(role)}</span>
            </div>
            <button
              type="button"
              className="button ghost small"
              onClick={() => void logout()}
              disabled={loggingOut}
            >
              {loggingOut ? '退出中…' : '退出'}
            </button>
          </div>
        </div>
      </aside>

      <div className="main-area">
        <header className="topbar">
          <div>
            <p className="eyebrow">远程巡检</p>
            <h1>{current?.label ?? '总览'}</h1>
          </div>
          <div className="topbar-meta">
            {error && <span className="text-warn">状态刷新异常</span>}
            <span className="muted small">每 1 秒刷新</span>
          </div>
        </header>
        <main className="content">{children}</main>
      </div>
    </div>
  );
}
