import { useState, type FormEvent } from 'react';
import type { ApiClient } from '../api/client';
import type { PatrolRoute, PatrolTask } from '../api/types';
import { Modal, errorMessage } from '../components/UI';
import { fromDateTimeLocal, toDateTimeLocal } from '../utils/format';

const STATUSES = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PLANNED', label: '已计划' },
  { value: 'CANCELLED', label: '已取消' },
];

export function TaskDialog({
  api,
  routes,
  initial,
  onClose,
  onSaved,
}: {
  api: ApiClient;
  routes: PatrolRoute[];
  initial?: PatrolTask | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? '');
  const [routeId, setRouteId] = useState<number>(
    initial?.routeId ?? routes[0]?.id ?? 0,
  );
  const [status, setStatus] = useState(initial?.status ?? 'DRAFT');
  const [plannedAt, setPlannedAt] = useState(toDateTimeLocal(initial?.plannedAt ?? null));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (!name.trim()) {
      setError('请输入任务名称。');
      return;
    }
    if (!routeId) {
      setError('请选择路线。');
      return;
    }
    if (status === 'PLANNED' && !plannedAt) {
      setError('状态为“已计划”时必须选择计划时间。');
      return;
    }
    const plannedIso = plannedAt ? fromDateTimeLocal(plannedAt) : null;
    if (plannedAt && !plannedIso) {
      setError('计划时间格式不正确。');
      return;
    }

    setBusy(true);
    setError(null);
    try {
      const input = {
        name: name.trim(),
        routeId,
        status,
        plannedAt: plannedIso,
      };
      if (initial) {
        await api.updateTask(initial.id, input);
      } else {
        await api.createTask(input);
      }
      onSaved();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      title={initial ? '编辑任务' : '新建任务'}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="button ghost" onClick={onClose} disabled={busy}>
            取消
          </button>
          <button type="submit" form="task-form" className="button primary" disabled={busy}>
            {busy ? '保存中…' : '保存'}
          </button>
        </>
      }
    >
      <form id="task-form" className="form" onSubmit={submit} noValidate>
        {error && (
          <div className="notice notice-error" role="alert">
            {error}
          </div>
        )}
        <label className="field">
          <span>任务名称</span>
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={80}
            disabled={busy}
            autoFocus
          />
        </label>
        <label className="field">
          <span>路线</span>
          <select
            value={routeId}
            onChange={(event) => setRouteId(Number(event.target.value))}
            disabled={busy || routes.length === 0}
          >
            {routes.map((route) => (
              <option key={route.id} value={route.id}>
                {route.name}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>状态</span>
          <select
            value={status}
            onChange={(event) => setStatus(event.target.value)}
            disabled={busy}
          >
            {STATUSES.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>计划时间{status === 'PLANNED' ? '（必填）' : '（可选）'}</span>
          <input
            type="datetime-local"
            value={plannedAt}
            onChange={(event) => setPlannedAt(event.target.value)}
            disabled={busy}
          />
        </label>
        <p className="fine-print">任务只维护计划数据，不会自动下发或启动巡检。</p>
      </form>
    </Modal>
  );
}
