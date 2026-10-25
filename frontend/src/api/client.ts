import { API_BASE_URL, REQUEST_TIMEOUT_MS } from '../config';
import type {
  AlarmItem,
  ControlLogItem,
  ControlResult,
  LoginResult,
  PageResult,
  PatrolRoute,
  PatrolTask,
  RobotInfo,
  RobotStatus,
  RouteInput,
  TaskInput,
  UserView,
} from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }

  get unauthorized(): boolean {
    return this.status === 401;
  }
}

type FetchLike = typeof fetch;

interface RequestOptions {
  method?: string;
  body?: unknown;
  query?: Record<string, string | number | null | undefined>;
  authenticated?: boolean;
}

interface Envelope {
  code?: unknown;
  message?: unknown;
  data?: unknown;
}

function fallbackMessage(status: number): string {
  const messages: Record<number, string> = {
    400: '请求参数不正确。',
    401: '登录状态无效或已过期。',
    403: '当前账号无权执行此操作。',
    404: '请求的资源不存在。',
    409: '当前状态下无法执行该操作。',
    415: '请求格式不受支持。',
    429: '请求过于频繁，请稍后再试。',
    503: '服务暂时不可用，请稍后重试。',
  };
  return messages[status] ?? `请求失败（HTTP ${status}）。`;
}

/**
 * Typed backend client. The JWT stays in a private field (memory only); it is
 * never written to localStorage, a URL or a log.
 */
export class ApiClient {
  private token: string | null = null;
  private unauthorizedNotified = false;
  private readonly fetchImpl: FetchLike;

  onUnauthorized: (() => void) | null = null;

  constructor(fetchImpl: FetchLike = fetch) {
    this.fetchImpl = fetchImpl;
  }

  setToken(token: string | null): void {
    this.token = token;
    this.unauthorizedNotified = false;
  }

  async login(username: string, password: string): Promise<LoginResult> {
    const data = await this.request<{ token?: unknown; user?: unknown }>(
      '/api/v1/auth/login',
      { method: 'POST', body: { username, password }, authenticated: false },
    );
    if (typeof data?.token !== 'string' || !data.token) {
      throw new ApiError(500, 'BAD_RESPONSE', '登录响应缺少令牌。');
    }
    this.setToken(data.token);
    const user = data.user as UserView | undefined;
    return {
      token: data.token,
      user: user ?? (await this.me()),
    };
  }

  me(): Promise<UserView> {
    return this.request<UserView>('/api/v1/auth/me');
  }

  robot(): Promise<RobotInfo> {
    return this.request<RobotInfo>('/api/v1/robot');
  }

  robotStatus(): Promise<RobotStatus> {
    return this.request<RobotStatus>('/api/v1/robot/status');
  }

  cameraStatus(): Promise<{ enabled: boolean; relay: { online: boolean; viewers: number } }> {
    return this.request('/api/v1/camera/status');
  }

  navigationStatus(): Promise<import('./types').NavigationStatus> {
    return this.request('/api/v1/navigation');
  }

  startNavigation(input: {mapId: string; points: Array<{x:number;y:number;yaw:number}>; cycles:number}): Promise<{accepted:boolean;missionId:string;seq:number}> {
    return this.request('/api/v1/navigation/missions', { method:'POST', body:input });
  }

  cancelNavigation(): Promise<unknown> {
    return this.request('/api/v1/navigation/cancel', {method:'POST'});
  }

  /** Streaming auth stays in the header, never in an img/video URL or browser storage. */
  async cameraResponse(kind: 'stream' | 'snapshot', signal: AbortSignal): Promise<Response> {
    const tokenAtSend = this.token;
    const response = await this.fetchImpl.call(globalThis, this.buildUrl(`/api/v1/camera/${kind}`), {
      headers: tokenAtSend ? { Authorization: `Bearer ${tokenAtSend}` } : {},
      credentials: 'omit', cache: 'no-store', signal,
    });
    if (!response.ok) {
      if (response.status === 401) this.notifyUnauthorized(tokenAtSend);
      const body = await response.json().catch(() => null) as Envelope | null;
      throw new ApiError(response.status, typeof body?.code === 'string' ? body.code : 'CAMERA_ERROR',
        typeof body?.message === 'string' ? body.message : fallbackMessage(response.status));
    }
    return response;
  }

  control(command: string): Promise<ControlResult> {
    return this.request<ControlResult>('/api/v1/robot/control', {
      method: 'POST',
      body: { command },
    });
  }

  alarms(params: {
    page?: number;
    size?: number;
    level?: string;
    status?: string;
  } = {}): Promise<PageResult<AlarmItem>> {
    return this.request<PageResult<AlarmItem>>('/api/v1/alarms', {
      query: {
        page: params.page ?? 1,
        size: params.size ?? 20,
        level: params.level,
        status: params.status,
      },
    });
  }

  acknowledgeAlarm(id: number): Promise<AlarmItem> {
    return this.request<AlarmItem>(`/api/v1/alarms/${id}/ack`, { method: 'PATCH' });
  }

  controlLogs(params: {
    page?: number;
    size?: number;
    operation?: string;
  } = {}): Promise<PageResult<ControlLogItem>> {
    return this.request<PageResult<ControlLogItem>>('/api/v1/control-logs', {
      query: {
        page: params.page ?? 1,
        size: params.size ?? 20,
        operation: params.operation,
      },
    });
  }

  routes(params: { page?: number; size?: number } = {}): Promise<PageResult<PatrolRoute>> {
    return this.request<PageResult<PatrolRoute>>('/api/v1/routes', {
      query: { page: params.page ?? 1, size: params.size ?? 20 },
    });
  }

  route(id: number): Promise<PatrolRoute> {
    return this.request<PatrolRoute>(`/api/v1/routes/${id}`);
  }

  createRoute(input: RouteInput): Promise<PatrolRoute> {
    return this.request<PatrolRoute>('/api/v1/routes', { method: 'POST', body: input });
  }

  updateRoute(id: number, input: RouteInput): Promise<PatrolRoute> {
    return this.request<PatrolRoute>(`/api/v1/routes/${id}`, {
      method: 'PUT',
      body: input,
    });
  }

  async deleteRoute(id: number): Promise<void> {
    await this.request<null>(`/api/v1/routes/${id}`, { method: 'DELETE' });
  }

  tasks(params: {
    page?: number;
    size?: number;
    status?: string;
  } = {}): Promise<PageResult<PatrolTask>> {
    return this.request<PageResult<PatrolTask>>('/api/v1/tasks', {
      query: {
        page: params.page ?? 1,
        size: params.size ?? 20,
        status: params.status,
      },
    });
  }

  task(id: number): Promise<PatrolTask> {
    return this.request<PatrolTask>(`/api/v1/tasks/${id}`);
  }

  createTask(input: TaskInput): Promise<PatrolTask> {
    return this.request<PatrolTask>('/api/v1/tasks', { method: 'POST', body: input });
  }

  updateTask(id: number, input: TaskInput): Promise<PatrolTask> {
    return this.request<PatrolTask>(`/api/v1/tasks/${id}`, {
      method: 'PUT',
      body: input,
    });
  }

  async deleteTask(id: number): Promise<void> {
    await this.request<null>(`/api/v1/tasks/${id}`, { method: 'DELETE' });
  }

  private buildUrl(path: string, query?: RequestOptions['query']): string {
    const base = API_BASE_URL ? `${API_BASE_URL}${path}` : path;
    if (!query) return base;
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== '') {
        params.set(key, String(value));
      }
    }
    const suffix = params.toString();
    return suffix ? `${base}?${suffix}` : base;
  }

  private async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
    }
    const authenticated = options.authenticated !== false;
    const tokenAtSend = this.token;
    if (authenticated && tokenAtSend) {
      headers.Authorization = `Bearer ${tokenAtSend}`;
    }

    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    let response: Response;
    try {
      response = await this.fetchImpl.call(globalThis, this.buildUrl(path, options.query), {
        method: options.method ?? 'GET',
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: controller.signal,
        credentials: 'omit',
      });
    } catch {
      throw new ApiError(0, 'NETWORK_ERROR', '网络请求失败，请检查网络后重试。');
    } finally {
      window.clearTimeout(timeout);
    }

    let envelope: Envelope | null = null;
    try {
      const text = await response.text();
      envelope = text ? (JSON.parse(text) as Envelope) : null;
    } catch {
      envelope = null;
    }

    if (!response.ok) {
      if (response.status === 401 && authenticated) {
        this.notifyUnauthorized(tokenAtSend);
      }
      const code =
        typeof envelope?.code === 'string' && envelope.code
          ? envelope.code
          : `HTTP_${response.status}`;
      const message =
        typeof envelope?.message === 'string' && envelope.message
          ? envelope.message
          : fallbackMessage(response.status);
      throw new ApiError(response.status, code, message);
    }

    if (!envelope) {
      throw new ApiError(500, 'BAD_RESPONSE', '服务器返回的数据格式不正确。');
    }
    if (typeof envelope.code === 'string' && envelope.code !== 'SUCCESS') {
      throw new ApiError(
        500,
        envelope.code,
        typeof envelope.message === 'string' ? envelope.message : '操作失败。',
      );
    }
    return envelope.data as T;
  }

  private notifyUnauthorized(tokenAtSend: string | null): void {
    // An old in-flight request must not clear a newer session. This mirrors the
    // session-epoch guard used by the backend console tests.
    if (tokenAtSend === null || this.token !== tokenAtSend) return;
    if (this.unauthorizedNotified) return;
    this.unauthorizedNotified = true;
    this.onUnauthorized?.();
  }
}
