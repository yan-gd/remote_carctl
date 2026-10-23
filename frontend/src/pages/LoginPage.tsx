import { useState, type FormEvent } from 'react';

export function LoginPage({
  onLogin,
  busy,
  error,
  notice,
}: {
  onLogin: (username: string, password: string) => Promise<void>;
  busy: boolean;
  error: string | null;
  notice: string | null;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [validation, setValidation] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (!username.trim() || !password) {
      setValidation('请输入用户名和密码。');
      return;
    }
    setValidation(null);
    await onLogin(username.trim(), password);
  }

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <p className="kicker">SCOUT MINI · REMOTE PATROL</p>
        <h1 id="login-title">远程巡检控制台</h1>
        <p className="login-sub">使用已授权的巡检账号登录。</p>

        {notice && <div className="notice notice-info">{notice}</div>}
        {(error || validation) && (
          <div className="notice notice-error" role="alert">
            {validation ?? error}
          </div>
        )}

        <form onSubmit={submit} noValidate>
          <label className="field">
            <span>用户名</span>
            <input
              name="username"
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              disabled={busy}
              autoFocus
            />
          </label>
          <label className="field">
            <span>密码</span>
            <input
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={busy}
            />
          </label>
          <button type="submit" className="button primary wide" disabled={busy}>
            {busy ? '登录中…' : '登录'}
          </button>
        </form>

        <p className="fine-print login-security">
          令牌仅保存在当前页面内存中，刷新即退出。请通过 HTTPS 访问。
        </p>
      </section>

      <footer className="login-foot">
        <span>SCOUT MINI</span>
        <span>REMOTE PATROL CONSOLE</span>
      </footer>
    </main>
  );
}
