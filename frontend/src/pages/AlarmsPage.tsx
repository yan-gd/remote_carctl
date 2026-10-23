import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { AlarmItem } from '../api/types';
import {
  Card,
  Empty,
  ErrorNotice,
  Pager,
  StatusPill,
  errorMessage,
} from '../components/UI';
import { alarmLevelLabel, formatDateTime } from '../utils/format';

const LEVELS = [
  { value: '', label: '全部级别' },
  { value: 'INFO', label: '提示' },
  { value: 'WARNING', label: '警告' },
  { value: 'CRITICAL', label: '严重' },
];

const STATUSES = [
  { value: '', label: '全部状态' },
  { value: 'UNACKNOWLEDGED', label: '未确认' },
  { value: 'ACKNOWLEDGED', label: '已确认' },
];

export function AlarmsPage({ api, role }: { api: ApiClient; role: string }) {
  const [items, setItems] = useState<AlarmItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [level, setLevel] = useState('');
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const result = await api.alarms({ page, size: 20, level, status });
      if (id !== requestId.current) return;
      setItems(result.items);
      setTotal(result.total);
    } catch (caught) {
      if (id === requestId.current) setError(errorMessage(caught));
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [api, level, page, status]);

  useEffect(() => {
    void load();
  }, [load]);

  async function acknowledge(item: AlarmItem) {
    setBusyId(item.id);
    setError(null);
    try {
      await api.acknowledgeAlarm(item.id);
      await load();
    } catch (caught) {
      setError(errorMessage(caught));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <Card
      title="告警"
      actions={
        <>
          <select
            aria-label="告警级别"
            value={level}
            onChange={(event) => {
              setPage(1);
              setLevel(event.target.value);
            }}
          >
            {LEVELS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
          <select
            aria-label="告警状态"
            value={status}
            onChange={(event) => {
              setPage(1);
              setStatus(event.target.value);
            }}
          >
            {STATUSES.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
          <button type="button" className="button ghost small" onClick={() => void load()}>
            刷新
          </button>
        </>
      }
    >
      {error && <ErrorNotice message={error} onRetry={() => void load()} />}
      {loading && items.length === 0 ? (
        <Empty>正在加载告警…</Empty>
      ) : items.length === 0 ? (
        <Empty>没有符合条件的告警。</Empty>
      ) : (
        <div className="list">
          {items.map((item) => (
            <article className="list-item" key={item.id}>
              <div className="list-main">
                <div className="pill-row">
                  <StatusPill tone={alarmTone(item.level)}>
                    {alarmLevelLabel(item.level)}
                  </StatusPill>
                  <StatusPill tone={item.status === 'ACKNOWLEDGED' ? 'ok' : 'warn'}>
                    {item.status === 'ACKNOWLEDGED' ? '已确认' : '未确认'}
                  </StatusPill>
                  <span className="muted small">{item.source}</span>
                </div>
                <h3>{item.title}</h3>
                <p className="muted">{item.description ?? '—'}</p>
                <span className="fine-print">{formatDateTime(item.occurredAt)}</span>
              </div>
              {role === 'MONITOR' && item.status !== 'ACKNOWLEDGED' && (
                <button
                  type="button"
                  className="button small"
                  disabled={busyId === item.id}
                  onClick={() => void acknowledge(item)}
                >
                  {busyId === item.id ? '确认中…' : '确认'}
                </button>
              )}
            </article>
          ))}
        </div>
      )}
      <Pager page={page} size={20} total={total} onChange={setPage} />
    </Card>
  );
}

function alarmTone(level: string): 'bad' | 'warn' | 'neutral' {
  if (level === 'CRITICAL') return 'bad';
  if (level === 'WARNING') return 'warn';
  return 'neutral';
}
