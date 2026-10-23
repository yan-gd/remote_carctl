import { useEffect, useRef } from 'react';
import type { ApiClient } from '../api/client';
import { HoldController } from '../control/HoldController';

export function useHoldControl(
  api: ApiClient,
  onError: (error: unknown) => void,
): HoldController {
  const controller = useRef<HoldController | null>(null);
  if (!controller.current) {
    controller.current = new HoldController((command) => api.control(command), onError);
  }
  useEffect(() => {
    const current = controller.current;
    return () => current?.dispose();
  }, []);
  return controller.current;
}
