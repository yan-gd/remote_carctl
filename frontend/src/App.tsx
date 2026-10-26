import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiClient } from './api/client';
import type { UserView } from './api/types';
import { apiBaseUrlProblem } from './config';
import { Atmosphere } from './components/Atmosphere';
import { Shell, type Tab } from './components/Layout';
import { errorMessage } from './components/UI';
import { AlarmsPage } from './pages/AlarmsPage';
import { ControlPage } from './pages/ControlPage';
import { DashboardPage } from './pages/DashboardPage';
import { permissions } from './auth/permissions';
import { LoginPage } from './pages/LoginPage';
import { LogsPage } from './pages/LogsPage';
import { CameraPage } from './pages/CameraPage';
import { NavigationPage } from './pages/NavigationPage';
import { RobotProvider } from './robot/RobotContext';

export default function App() {
  const apiRef = useRef<ApiClient | null>(null);
  if (!apiRef.current) apiRef.current = new ApiClient();
  const api = apiRef.current;

  const [user, setUser] = useState<UserView | null>(null);
  const [tab, setTab] = useState<Tab>('dashboard');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const configProblem = apiBaseUrlProblem();

  useEffect(() => {
    api.onUnauthorized = () => {
      setUser(null);
      setBusy(false);
      setError(null);
      setNotice('登录状态已过期，请重新登录。');
    };
    return () => {
      if (api.onUnauthorized) api.onUnauthorized = null;
    };
  }, [api]);

  async function login(username: string, password: string) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await api.login(username, password);
      setUser(result.user);
      setTab(result.user.role==='MONITOR'?'alarms':'dashboard');
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    // Best-effort STOP before the token is discarded; the robot watchdog is the
    // fallback if the network is already gone.
    try {
      if(user&&permissions(user.role).stop)await api.control('STOP');
    } catch {
      // Ignore: logout must still clear the local session.
    }
    api.setToken(null);
    setUser(null);
    setError(null);
    setNotice(null);
    setTab('dashboard');
  }

  let content: ReactNode;

  if (configProblem) {
    content = (
      <main className="login-page">
        <section className="login-panel login-panel-solo">
          <p className="kicker">CONFIGURATION</p>
          <h1>不安全的访问配置</h1>
          <p className="notice notice-error">{configProblem}</p>
          <p className="muted">
            请通过 HTTPS 访问，或仅在开发环境使用本机回环 HTTP。生产构建不要设置
            <code>VITE_API_BASE_URL</code>，让页面同源请求 nginx。
          </p>
        </section>
      </main>
    );
  } else if (!user) {
    content = (
      <LoginPage onLogin={login} busy={busy} error={error} notice={notice} />
    );
  } else {
    content = (
      <RobotProvider api={api}>
        <Shell
          api={api}
          username={user.username}
          role={user.role}
          active={tab}
          onNavigate={setTab}
          onLogout={logout}
          onStop={async () => { await api.control('STOP'); }}
        >
          {tab === 'dashboard' && <DashboardPage />}
          {tab === 'camera' && <CameraPage api={api} />}
          {tab === 'navigation' && <NavigationPage api={api} role={user.role} />}
          {tab === 'control' && permissions(user.role).move && <ControlPage api={api} role={user.role} />}
          {tab === 'alarms' && <AlarmsPage api={api} role={user.role} />}
          {tab === 'logs' && <LogsPage api={api} role={user.role} />}
        </Shell>
      </RobotProvider>
    );
  }

  return (
    <div className="app-root">
      {(!user || tab === 'dashboard') && <Atmosphere />}
      {content}
    </div>
  );
}
