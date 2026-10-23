import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { ApiClient } from '../api/client';
import type { ControlResult, RobotInfo, RobotStatus } from '../api/types';
import { POLL_INTERVAL_MS } from '../config';

interface RobotContextValue {
  info: RobotInfo | null;
  status: RobotStatus | null;
  error: string | null;
  updatedAt: Date | null;
  refresh: () => Promise<void>;
  control: (command: string) => Promise<ControlResult>;
  stopMotion: () => Promise<void>;
}

const RobotContext = createContext<RobotContextValue | null>(null);

export function RobotProvider({ api, children }: { api: ApiClient; children: ReactNode }) {
  const [info, setInfo] = useState<RobotInfo | null>(null);
  const [status, setStatus] = useState<RobotStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  const ticks = useRef(0);

  const refresh = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    try {
      const next = await api.robotStatus();
      if (!mounted.current) return;
      setStatus(next);
      setUpdatedAt(new Date());
      setError(null);
      if (ticks.current === 0 || ticks.current % 30 === 0) {
        try {
          const robot = await api.robot();
          if (mounted.current) setInfo(robot);
        } catch {
          // Non-fatal: the status call remains the source of connectivity.
        }
      }
      ticks.current += 1;
    } catch (caught) {
      if (!mounted.current) return;
      setError(caught instanceof Error ? caught.message : '状态刷新失败，请稍后重试。');
    } finally {
      inFlight.current = false;
    }
  }, [api]);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const timer = window.setInterval(() => {
      void refresh();
    }, POLL_INTERVAL_MS);
    return () => {
      mounted.current = false;
      window.clearInterval(timer);
    };
  }, [refresh]);

  const control = useCallback((command: string) => api.control(command), [api]);
  const stopMotion = useCallback(async () => {
    try {
      await api.control('STOP');
    } catch {
      // The robot-side watchdog is the fallback.
    }
  }, [api]);

  return (
    <RobotContext.Provider
      value={{ info, status, error, updatedAt, refresh, control, stopMotion }}
    >
      {children}
    </RobotContext.Provider>
  );
}

export function useRobot(): RobotContextValue {
  const value = useContext(RobotContext);
  if (!value) throw new Error('useRobot must be used inside RobotProvider');
  return value;
}
