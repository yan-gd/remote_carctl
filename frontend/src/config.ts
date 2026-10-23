/** Compile-time configuration and the only place that checks API transport safety. */

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '')
  .trim()
  .replace(/\/+$/, '');

export const REQUEST_TIMEOUT_MS = 12_000;
export const POLL_INTERVAL_MS = 1_000;
export const HOLD_INTERVAL_MS = 200;

export function apiBaseUrlProblem(): string | null {
  if (!API_BASE_URL) return null;
  let url: URL;
  try {
    url = new URL(API_BASE_URL);
  } catch {
    return `VITE_API_BASE_URL 不是有效地址：${API_BASE_URL}`;
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return 'VITE_API_BASE_URL 只支持 http(s)。';
  }
  const loopback =
    url.hostname === 'localhost' ||
    url.hostname === '127.0.0.1' ||
    url.hostname === '::1';
  if (url.protocol === 'http:' && !loopback) {
    return '生产环境必须通过 HTTPS 访问后端，当前 VITE_API_BASE_URL 是明文 HTTP。';
  }
  if (url.pathname !== '/' && url.pathname !== '') {
    return 'VITE_API_BASE_URL 不能带路径，只填写协议、主机和端口。';
  }
  return null;
}
