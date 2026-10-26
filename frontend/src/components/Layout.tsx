import { useEffect, useState, type ReactNode } from 'react';
import { errorMessage } from './UI';
import { useRobot } from '../robot/RobotContext';
import { formatRelative, roleLabel } from '../utils/format';
import { permissions, roleDescription } from '../auth/permissions';
import type { ApiClient } from '../api/client';

export type Tab = 'dashboard' | 'control' | 'alarms' | 'logs' | 'camera' | 'navigation';

const NAV: Array<{ id: Tab; label: string; description: string }> = [
  {
    id: 'dashboard',
    label: '总览',
    description: '机器人实时遥测与最近执行回执。',
  },
  {
    id: 'control',
    label: '控制',
    description: '查看车载画面，按住行驶，松开停止。',
  },
  { id: 'navigation', label: '导航', description: '在线建图、地图定位与选点导航，也可使用附近目标模式。' },
  { id: 'camera', label: '摄像头', description: '实时观看与拍摄，服务端仅转发、不保存。' },
  {
    id: 'alarms',
    label: '告警',
    description: '查看设备与导航告警，按权限确认并追溯处理记录。',
  },
  {
    id: 'logs',
    label: '日志',
    description: '登录、控制与巡检数据变更的操作记录。',
  },
];

function clockText(value: Date | null): string {
  if (!value) return '--:--:--';
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(value);
}

export function Shell({
  username,
  role,
  active,
  onNavigate,
  onLogout,
  onStop,
  children,
  api,
}: {
  username: string;
  role: string;
  active: Tab;
  onNavigate: (tab: Tab) => void;
  onLogout: () => Promise<void>;
  onStop: () => Promise<void>;
  children: ReactNode;
  api?: ApiClient;
}) {
  const { status, info, error, updatedAt } = useRobot();
  const [loggingOut, setLoggingOut] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [stopNotice, setStopNotice] = useState<{failed:boolean;message:string}|null>(null);
  const online = status?.online ?? info?.online ?? false;
  const current = NAV.find((item) => item.id === active) ?? NAV[0];
  const access = permissions(role);
  const [unread, setUnread] = useState<number|null>(null);
  const [alarmError,setAlarmError] = useState(false);

  useEffect(()=>{
    if(!api)return;
    let disposed=false,inFlight=false;
    const refresh=async()=>{
      if(inFlight)return;inFlight=true;
      try{const result=await api.alarms({page:1,size:1,status:'UNACKNOWLEDGED'});
        if(!disposed){setUnread(result.total);setAlarmError(false);}}
      catch{if(!disposed){setUnread(null);setAlarmError(true);}}
      finally{inFlight=false;}
    };
    void refresh();const timer=window.setInterval(()=>void refresh(),3000);
    return()=>{disposed=true;window.clearInterval(timer);};
  },[api]);

  useEffect(() => {
    if (!stopNotice) return;
    const timer = window.setTimeout(() => setStopNotice(null), 6000);
    return () => window.clearTimeout(timer);
  }, [stopNotice]);

  async function stop() {
    if (stopping) return;
    setStopping(true);
    setStopNotice(null);
    try {
      await onStop();
      setStopNotice({failed:false,message:'STOP已发送，请核对回执和实际停车状态。'});
    } catch (caught) { setStopNotice({failed:true,message:errorMessage(caught)}); }
    finally { setStopping(false); }
  }

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
    <div className={`app-shell view-${active}`}>
      <aside className="sidebar">
        <div className="brand">
          <strong>SCOUT</strong>
          <span>远程巡检控制台</span>
        </div>

        <nav className="nav" aria-label="主导航">
          {NAV.filter(item=>item.id!=='control'||access.move).map((item) => (
            <button
              key={item.id}
              type="button"
              className={`nav-item ${active === item.id ? 'active' : ''}`}
              onClick={() => onNavigate(item.id)}
              aria-current={active === item.id ? 'page' : undefined}
              title={item.description}
            >
              {item.label}{item.id==='alarms'&&unread!==null&&unread>0?` (${unread})`:''}
            </button>
          ))}
        </nav>

        <div className="sidebar-foot">
          <div className="connection">
            <div className="connection-head">
              <span className={`live-dot ${online ? 'is-online' : ''}`} aria-hidden="true" />
              <span>{online ? '机器人在线' : '机器人离线'}</span>
            </div>
            <span className="connection-meta">
              {status?.lastSeenAt ? `心跳 ${formatRelative(status.lastSeenAt)}` : '等待心跳'}
              {' · '}
              {status?.robotId ?? info?.robotId ?? 'SCOUT-001'}
            </span>
          </div>

          <div className="user-box">
            <div className="user-meta">
              <strong>{username}</strong>
              <span>{roleLabel(role)}</span>
              <span className="fine-print">{roleDescription(role)}</span>
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
          <div className="topbar-heading">
            <h1>{current.label}</h1>
            <p>{current.description}</p>
          </div>
          <div className="topbar-meta">
            {access.stop && <button type="button"
              className="button danger small" disabled={stopping} onClick={() => void stop()}>
              {stopping ? '正在发送…' : '立即停止'}</button>}
            {error && <span className="text-warn">状态刷新异常</span>}
            <span className="topbar-sync">
              <span className={`live-dot ${online ? 'is-online' : ''}`} aria-hidden="true" />
              {online ? '实时同步' : '等待连接'}
            </span>
            <span className="topbar-time">{clockText(updatedAt)}</span>
          </div>
        </header>
        {stopNotice && <div role={stopNotice.failed ? 'alert' : 'status'}
          className={`global-control-notice notice ${stopNotice.failed ? 'notice-error' : 'notice-info'}`}>
          {stopNotice.message}</div>}
        {active!=='alarms'&&(alarmError||(unread!==null&&unread>0))&&<div className="global-control-notice notice notice-info">
          <button className="button small" onClick={()=>onNavigate('alarms')}>
            {alarmError?'告警同步异常，打开告警页重试':`${unread} 条告警待确认 · 查看告警`}
          </button>
        </div>}
        <main className="content">{children}</main>
      </div>
    </div>
  );
}
