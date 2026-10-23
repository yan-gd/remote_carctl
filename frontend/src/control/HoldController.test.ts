import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HoldController } from './HoldController';

describe('HoldController', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('sends the command immediately and repeated keepalives', async () => {
    const send = vi.fn().mockResolvedValue({});
    const controller = new HoldController(send, undefined, 200);

    await controller.start('FORWARD');
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenLastCalledWith('FORWARD');

    await vi.advanceTimersByTimeAsync(450);
    expect(send.mock.calls.length).toBeGreaterThanOrEqual(3);

    await controller.stop();
    expect(send).toHaveBeenLastCalledWith('STOP');
    expect(controller.active).toBe(false);
  });

  it('waits for an in-flight keepalive before sending STOP', async () => {
    let resolveFirst: (value: unknown) => void = () => undefined;
    const first = new Promise<unknown>((resolve) => {
      resolveFirst = resolve;
    });
    const send = vi.fn().mockReturnValueOnce(first).mockResolvedValue({});
    const controller = new HoldController(send, undefined, 200);

    const started = controller.start('FORWARD');
    const stopped = controller.stop();

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenLastCalledWith('FORWARD');

    resolveFirst({});
    await started;
    await stopped;

    expect(send).toHaveBeenLastCalledWith('STOP');
    expect(send.mock.calls.filter(([command]) => command === 'STOP')).toHaveLength(1);
  });

  it('reports an error and stops the timer when a keepalive fails', async () => {
    const error = new Error('network down');
    const onError = vi.fn();
    const send = vi
      .fn()
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(error);
    const controller = new HoldController(send, onError, 200);

    await controller.start('FORWARD');
    await vi.advanceTimersByTimeAsync(200);

    expect(onError).toHaveBeenCalledWith(error);
    expect(controller.active).toBe(false);

    const calls = send.mock.calls.length;
    await vi.advanceTimersByTimeAsync(500);
    expect(send.mock.calls.length).toBe(calls);
  });

  it('does not start another command while STOP is in flight', async () => {
    const send = vi.fn().mockResolvedValue({});
    const controller = new HoldController(send, undefined, 200);

    await controller.start('FORWARD');
    await controller.stop();
    expect(send.mock.calls.filter(([command]) => command === 'STOP')).toHaveLength(1);
  });
});
