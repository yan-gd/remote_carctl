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
      <section className="login-panel">
        <div className="brand-mark" aria-hidden="true">
          S
        </div>
        <p className="eyebrow">SCOUT MINI</p>
        <h1>远程巡检控制台</h1>
        <p className="muted">登录后查看机器人状态、执行巡检计划与控制。</p>

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

        <p className="fine-print">
          令牌只保存在当前页面内存中，刷新即退出；请通过 HTTPS 访问。
        </p>
      </section>
    </main>
  );
}
