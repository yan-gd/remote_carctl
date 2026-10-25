import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError } from './client';

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const user = { id: 1, username: 'demo_operator', role: 'OPERATOR' };

describe('ApiClient', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let client: ApiClient;

  beforeEach(() => {
    fetchMock = vi.fn();
    client = new ApiClient(fetchMock as unknown as typeof fetch);
  });

  it('authenticates camera bytes in the header with no cache or token URL', async () => {
    client.setToken('camera-token');
    fetchMock.mockResolvedValueOnce(new Response(new Uint8Array([1, 2, 3])));
    await client.cameraResponse('snapshot', new AbortController().signal);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/camera/snapshot');
    expect(init.headers).toEqual({ Authorization: 'Bearer camera-token' });
    expect(init.cache).toBe('no-store');
    expect(init.credentials).toBe('omit');
  });

  it('expires the current session on camera 401 and surfaces device offline errors', async () => {
    client.setToken('expired-camera-token');
    const expired=vi.fn();client.onUnauthorized=expired;
    fetchMock.mockResolvedValueOnce(response({ code:'TOKEN_EXPIRED',message:'登录过期' },401));
    await expect(client.cameraResponse('stream',new AbortController().signal)).rejects.toMatchObject({status:401});
    expect(expired).toHaveBeenCalledOnce();
    fetchMock.mockResolvedValueOnce(response({code:'CAMERA_OFFLINE',message:'暂无实时画面'},503));
    await expect(client.cameraResponse('snapshot',new AbortController().signal)).rejects.toMatchObject({code:'CAMERA_OFFLINE'});
  });

  it('logs in without a token and sends the token on later requests', async () => {
    fetchMock.mockResolvedValueOnce(
      response({ code: 'SUCCESS', data: { token: 'jwt-token', user } }),
    );

    const result = await client.login('demo_operator', 'secret');
    expect(result.token).toBe('jwt-token');
    expect(result.user.username).toBe('demo_operator');

    const [loginUrl, loginInit] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(loginUrl).toBe('/api/v1/auth/login');
    expect(loginInit.method).toBe('POST');
    expect((loginInit.headers as Record<string, string>).Authorization).toBeUndefined();

    fetchMock.mockResolvedValueOnce(
      response({ code: 'SUCCESS', data: { robotId: 'SCOUT-001' } }),
    );
    await client.robotStatus();
    const [, statusInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect((statusInit.headers as Record<string, string>).Authorization).toBe(
      'Bearer jwt-token',
    );
  });

  it('surfaces the backend message and status', async () => {
    client.setToken('jwt-token');
    fetchMock.mockResolvedValueOnce(
      response({ code: 'FORBIDDEN', message: '当前账号无权执行此操作。' }, 403),
    );

    await expect(client.robotStatus()).rejects.toMatchObject({
      status: 403,
      code: 'FORBIDDEN',
      message: '当前账号无权执行此操作。',
    } satisfies Partial<ApiError>);
  });

  it('notifies once when an authenticated request returns 401', async () => {
    const onUnauthorized = vi.fn();
    client.onUnauthorized = onUnauthorized;
    client.setToken('expired');
    fetchMock.mockResolvedValue(
      response({ code: 'UNAUTHORIZED', message: '登录状态无效。' }, 401),
    );

    await expect(client.robotStatus()).rejects.toBeInstanceOf(ApiError);
    await expect(client.robot()).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('does not treat a failed login as an expired session', async () => {
    const onUnauthorized = vi.fn();
    client.onUnauthorized = onUnauthorized;
    fetchMock.mockResolvedValueOnce(
      response({ code: 'UNAUTHORIZED', message: '用户名或密码错误。' }, 401),
    );

    await expect(client.login('demo_operator', 'wrong')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('ignores a 401 from a request that used a previous token', async () => {
    const onUnauthorized = vi.fn();
    client.onUnauthorized = onUnauthorized;
    client.setToken('old-token');
    let resolveFetch: (value: Response) => void = () => undefined;
    fetchMock.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        resolveFetch = resolve;
      }),
    );

    const pending = client.robotStatus();
    client.setToken('new-token');
    resolveFetch(response({ code: 'UNAUTHORIZED', message: '旧会话已过期。' }, 401));

    await expect(pending).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('invokes fetch with the global receiver', async () => {
    const impl = vi.fn(function (this: unknown) {
      expect(this).toBe(globalThis);
      return Promise.resolve(response({ code: 'SUCCESS', data: { robotId: 'SCOUT-001' } }));
    });
    const local = new ApiClient(impl as unknown as typeof fetch);
    await local.robotStatus();
    expect(impl).toHaveBeenCalledTimes(1);
  });

  it('builds query strings and omits empty filters', async () => {
    client.setToken('jwt-token');
    fetchMock.mockResolvedValueOnce(
      response({ code: 'SUCCESS', data: { items: [], total: 0, page: 2, size: 10 } }),
    );

    await client.alarms({ page: 2, size: 10, level: 'WARNING', status: '' });
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe('/api/v1/alarms?page=2&size=10&level=WARNING');
  });
});
