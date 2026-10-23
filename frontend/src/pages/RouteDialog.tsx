import { useState, type FormEvent } from 'react';
import type { ApiClient } from '../api/client';
import type { PatrolRoute, RoutePoint } from '../api/types';
import { Modal, errorMessage } from '../components/UI';

interface PointDraft {
  x: string;
  y: string;
  yaw: string;
}

function initialPoints(route?: PatrolRoute | null): PointDraft[] {
  if (route?.points.length) {
    return route.points.map((point) => ({
      x: String(point.x),
      y: String(point.y),
      yaw: point.yaw === null ? '' : String(point.yaw),
    }));
  }
  return [{ x: '0', y: '0', yaw: '' }];
}

export function RouteDialog({
  api,
  initial,
  onClose,
  onSaved,
}: {
  api: ApiClient;
  initial?: PatrolRoute | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [points, setPoints] = useState<PointDraft[]>(() => initialPoints(initial));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function updatePoint(index: number, key: keyof PointDraft, value: string) {
    setPoints((current) =>
      current.map((point, i) => (i === index ? { ...point, [key]: value } : point)),
    );
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    if (!name.trim()) {
      setError('请输入路线名称。');
      return;
    }
    if (points.length === 0) {
      setError('至少需要一个点位。');
      return;
    }
    const parsed: RoutePoint[] = [];
    for (let index = 0; index < points.length; index += 1) {
      const point = points[index];
      if (point.x.trim() === '' || point.y.trim() === '') {
        setError(`点位 ${index + 1} 的 X/Y 不能为空。`);
        return;
      }
      const x = Number(point.x);
      const y = Number(point.y);
      const yaw = point.yaw.trim() === '' ? null : Number(point.yaw);
      if (!Number.isFinite(x) || !Number.isFinite(y)) {
        setError(`点位 ${index + 1} 的 X/Y 必须是有限数字。`);
        return;
      }
      if (yaw !== null && !Number.isFinite(yaw)) {
        setError(`点位 ${index + 1} 的航向角必须是有限数字，或留空。`);
        return;
      }
      parsed.push({ sequence: index, x, y, yaw });
    }

    setBusy(true);
    setError(null);
    try {
      const input = {
        name: name.trim(),
        description: description.trim() || null,
        points: parsed,
      };
      if (initial) {
        await api.updateRoute(initial.id, input);
      } else {
        await api.createRoute(input);
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
      title={initial ? '编辑路线' : '新建路线'}
      onClose={onClose}
      wide
      footer={
        <>
          <button type="button" className="button ghost" onClick={onClose} disabled={busy}>
            取消
          </button>
          <button type="submit" form="route-form" className="button primary" disabled={busy}>
            {busy ? '保存中…' : '保存'}
          </button>
        </>
      }
    >
      <form id="route-form" className="form" onSubmit={submit} noValidate>
        {error && (
          <div className="notice notice-error" role="alert">
            {error}
          </div>
        )}
        <label className="field">
          <span>路线名称</span>
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={80}
            disabled={busy}
            autoFocus
          />
        </label>
        <label className="field">
          <span>描述（可选）</span>
          <textarea
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            rows={2}
            maxLength={500}
            disabled={busy}
          />
        </label>

        <div className="field-head">
          <span>点位</span>
          <button
            type="button"
            className="button ghost small"
            onClick={() => setPoints((current) => [...current, { x: '0', y: '0', yaw: '' }])}
            disabled={busy}
          >
            + 添加点位
          </button>
        </div>

        <div className="point-list">
          {points.map((point, index) => (
            <div className="point-row" key={index}>
              <span className="point-index">{index + 1}</span>
              <label>
                <span>X (m)</span>
                <input
                  type="number"
                  step="any"
                  value={point.x}
                  onChange={(event) => updatePoint(index, 'x', event.target.value)}
                  disabled={busy}
                />
              </label>
              <label>
                <span>Y (m)</span>
                <input
                  type="number"
                  step="any"
                  value={point.y}
                  onChange={(event) => updatePoint(index, 'y', event.target.value)}
                  disabled={busy}
                />
              </label>
              <label>
                <span>航向角 (rad，可选)</span>
                <input
                  type="number"
                  step="any"
                  value={point.yaw}
                  onChange={(event) => updatePoint(index, 'yaw', event.target.value)}
                  disabled={busy}
                />
              </label>
              <button
                type="button"
                className="icon-button"
                aria-label={`删除点位 ${index + 1}`}
                onClick={() => setPoints((current) => current.filter((_, i) => i !== index))}
                disabled={busy || points.length <= 1}
              >
                ×
              </button>
            </div>
          ))}
        </div>
        <p className="fine-print">点位按当前顺序保存；路线只维护计划数据，不会自动下发。</p>
      </form>
    </Modal>
  );
}
