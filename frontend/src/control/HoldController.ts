import { HOLD_INTERVAL_MS } from '../config';

export type CommandSender = (command: string) => Promise<unknown>;

/**
 * Hold-to-drive loop: sends the command immediately and every 200 ms, never
 * overlapping requests, and sends STOP only after an in-flight keepalive
 * settles so a late FORWARD cannot follow STOP.
 */
export class HoldController {
  private timer: number | null = null;
  private command: string | null = null;
  private pending: Promise<unknown> | null = null;
  private disposed = false;

  constructor(
    private readonly send: CommandSender,
    private readonly onError?: (error: unknown) => void,
    private readonly period = HOLD_INTERVAL_MS,
  ) {}

  get active(): boolean {
    return this.command !== null;
  }

  async start(command: string): Promise<void> {
    if (this.disposed || command === 'STOP') return;
    if (this.command === command && this.timer !== null) return;
    this.cancelTimer();
    this.command = command;
    const first = this.send(command);
    this.pending = first;
    try {
      await first;
    } catch (error) {
      this.command = null;
      this.onError?.(error);
      return;
    } finally {
      if (this.pending === first) this.pending = null;
    }
    if (this.disposed || this.command !== command) return;
    this.timer = window.setInterval(() => {
      void this.tick(command);
    }, this.period);
  }

  /** Returns true if STOP was delivered. */
  async stop(force = false): Promise<boolean> {
    const wasHolding = this.command !== null;
    this.cancelTimer();
    this.command = null;
    if (this.pending) {
      try {
        await this.pending;
      } catch {
        // _tick reports errors; STOP is still attempted below.
      }
    }
    if (!wasHolding && !force) return true;
    try {
      await this.send('STOP');
      return true;
    } catch (error) {
      this.onError?.(error);
      return false;
    }
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    const wasHolding = this.command !== null;
    this.cancelTimer();
    this.command = null;
    if (wasHolding) {
      void this.send('STOP').catch(() => undefined);
    }
  }

  private async tick(command: string): Promise<void> {
    if (this.disposed || this.command !== command || this.pending) return;
    const request = this.send(command);
    this.pending = request;
    try {
      await request;
    } catch (error) {
      this.cancelTimer();
      if (this.command === command) this.command = null;
      this.onError?.(error);
    } finally {
      if (this.pending === request) this.pending = null;
    }
  }

  private cancelTimer(): void {
    if (this.timer !== null) {
      window.clearInterval(this.timer);
      this.timer = null;
    }
  }
}
