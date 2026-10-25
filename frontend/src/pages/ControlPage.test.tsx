import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ApiClient } from '../api/client';
import { ControlPage } from './ControlPage';
import { CameraPage } from './CameraPage';

vi.mock('../robot/RobotContext', () => ({ useRobot: () => ({
  status: { online: true, motionState: 'STOPPED', linearVelocity: 0, angularVelocity: 0, batteryVoltage: 25.2 },
  error: null,
}) }));

function client() {
  let writer: ReadableStreamDefaultController<Uint8Array>;
  let signal: AbortSignal;
  const cameraResponse = vi.fn(async (_kind: string, nextSignal: AbortSignal) => {
    signal = nextSignal;
    const body = new ReadableStream<Uint8Array>({ start(controller) {
      writer = controller;
      signal.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), { once: true });
    } });
    return new Response(body);
  });
  const control = vi.fn(async () => ({}));
  return {
    api: { cameraResponse, control } as unknown as ApiClient,
    cameraResponse, control,
    signal: () => signal,
    frame: () => writer.enqueue(new Uint8Array([0, 0, 0, 4, 255, 216, 255, 217])),
    fail: () => writer.error(new Error('视频连接中断')),
  };
}

describe('camera and manual controls together', () => {
  const close = vi.fn();
  const drawImage = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers();
    close.mockClear();
    drawImage.mockClear();
    vi.stubGlobal('createImageBitmap', vi.fn(async () => ({ width: 640, height: 480, close })));
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      fillRect: vi.fn(), drawImage, fillStyle: '',
    } as unknown as CanvasRenderingContext2D);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('automatically renders frames while hold, keepalive and release STOP keep working', async () => {
    const link = client();
    const view = render(<ControlPage api={link.api} role="OPERATOR" />);
    await act(async () => {});
    expect(link.cameraResponse).toHaveBeenCalledOnce();
    expect(link.cameraResponse).toHaveBeenCalledWith('stream', expect.any(AbortSignal));
    await act(async () => { link.frame(); });
    expect(drawImage).toHaveBeenCalledOnce();
    expect(close).toHaveBeenCalledOnce();
    expect(screen.getByText('实时画面')).toBeInTheDocument();

    await act(async () => { fireEvent.pointerDown(screen.getByRole('button', { name: '前进' })); });
    await act(async () => { await vi.advanceTimersByTimeAsync(450); });
    expect(link.control.mock.calls.length).toBeGreaterThanOrEqual(3);
    await act(async () => { fireEvent.pointerUp(screen.getByRole('button', { name: '前进' })); });
    expect(link.control).toHaveBeenLastCalledWith('STOP');
    expect(link.signal().aborted).toBe(false);
    view.unmount();
    expect(link.signal().aborted).toBe(true);
  });

  it('retries a stream failure without blocking STOP and still stops held motion on blur', async () => {
    const link = client();
    render(<ControlPage api={link.api} role="OPERATOR" />);
    await act(async () => {});
    await act(async () => { link.fail(); });
    expect(screen.getByRole('alert')).toHaveTextContent('正在自动重连');
    expect(screen.getByRole('button', { name: '断开画面' })).toBeEnabled();
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(link.cameraResponse).toHaveBeenCalledTimes(2);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '停止' })); });
    expect(link.control).toHaveBeenLastCalledWith('STOP');
    await act(async () => { fireEvent.pointerDown(screen.getByRole('button', { name: '前进' })); });
    await act(async () => { window.dispatchEvent(new Event('blur')); });
    expect(link.control).toHaveBeenLastCalledWith('STOP');
  });

  it('reconnects when the JPEG HTTP response stays open but frames freeze', async () => {
    const link = client();
    render(<ControlPage api={link.api} role="OPERATOR" />);
    await act(async () => {});
    await act(async () => { link.frame(); });
    expect(screen.getByText('实时画面')).toBeInTheDocument();

    await act(async () => { await vi.advanceTimersByTimeAsync(8100); });
    expect(link.signal().aborted).toBe(true);
    expect(screen.getByRole('alert')).toHaveTextContent('7 秒未更新');
    await act(async () => { await vi.advanceTimersByTimeAsync(1000); });
    expect(link.cameraResponse).toHaveBeenCalledTimes(2);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '停止' })); });
    expect(link.control).toHaveBeenLastCalledWith('STOP');
  });

  it.each(['MONITOR', 'VIEWER'])('allows %s to watch without granting drive permission', async role => {
    const link = client();
    render(<ControlPage api={link.api} role={role} />);
    await act(async () => {});
    expect(link.cameraResponse).toHaveBeenCalledOnce();
    expect(screen.getByRole('button', { name: '前进' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '停止' })).toHaveProperty('disabled', role === 'VIEWER');
    expect(link.control).not.toHaveBeenCalled();
  });

  it('keeps the dedicated camera page opt-in and releases a disconnected stream', async () => {
    const link = client();
    render(<CameraPage api={link.api} />);
    expect(link.cameraResponse).not.toHaveBeenCalled();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '连接画面' })); });
    await act(async () => { link.frame(); });
    expect(screen.getByText('实时画面')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '拍照' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '录制30秒内视频' })).toBeEnabled();
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '断开画面' })); });
    expect(link.signal().aborted).toBe(true);
    expect(screen.getByRole('button', { name: '录制30秒内视频' })).toBeDisabled();
  });
});
