import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { ControlLogItem } from '../api/types';
import { Card, Empty, ErrorNotice, Pager, StatusPill, errorMessage } from '../components/UI';
import { formatDateTime, operationLabel } from '../utils/format';
import { permissions } from '../auth/permissions';

const OPERATIONS = [
  { value: '', label: '全部操作' },
  { value: 'LOGIN', label: '登录' },
  { value: 'ROBOT_CONTROL', label: '机器人控制' },
  { value: 'NAVIGATION_START', label: '启动导航' },
  { value: 'NAVIGATION_CANCEL', label: '取消导航' },
  { value: 'NAVIGATION_END', label: '导航结束' },
  { value: 'ROUTE_CREATE', label: '创建路线' },
  { value: 'ROUTE_UPDATE', label: '修改路线' },
  { value: 'ROUTE_DELETE', label: '删除路线' },
  { value: 'TASK_CREATE', label: '创建任务' },
  { value: 'TASK_UPDATE', label: '修改任务' },
  { value: 'TASK_DELETE', label: '删除任务' },
  { value: 'ALARM_ACK', label: '确认告警' },
];

export function LogsPage({ api, role }: { api: ApiClient; role: string }) {
  const [items, setItems] = useState<ControlLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [operation, setOperation] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestId = useRef(0);
  const [from,setFrom]=useState(''),[to,setTo]=useState('');

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      if(from&&to&&from>to)throw new Error('开始时间不能晚于结束时间。');
      const result = await api.controlLogs({ page, size: 20, operation,
        from:from?new Date(from).toISOString():undefined,to:to?new Date(to).toISOString():undefined });
      if (id !== requestId.current) return;
      setItems(result.items);
      setTotal(result.total);
    } catch (caught) {
      if (id === requestId.current) setError(errorMessage(caught));
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [api, operation, page, from, to]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Card
      title="操作记录"
      actions={
        <>
          <select
            aria-label="操作类型"
            value={operation}
            onChange={(event) => {
              setPage(1);
              setOperation(event.target.value);
            }}
          >
            {OPERATIONS.map((item) => (
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
      <p className="muted">{permissions(role).allLogs?'可查询全部用户的操作日志。':'仅显示本人的操作日志。'}</p>
      <div className="mapping-controls history-filters">
        <label>开始时间<input type="datetime-local" value={from} onChange={e=>{setFrom(e.target.value);setPage(1);}}/></label>
        <label>结束时间<input type="datetime-local" value={to} onChange={e=>{setTo(e.target.value);setPage(1);}}/></label>
      </div>
      {error && <ErrorNotice message={error} onRetry={() => void load()} />}
      {loading && items.length === 0 ? (
        <Empty>正在加载日志…</Empty>
      ) : items.length === 0 ? (
        <Empty>没有符合条件的日志。</Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>操作者</th>
                <th>操作</th>
                <th>目标</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id}>
                  <td>{formatDateTime(item.occurredAt)}</td>
                  <td>{item.actorName}</td>
                  <td>{operationLabel(item.operation)}</td>
                  <td>{item.target}</td>
                  <td>
                    <StatusPill tone={item.result === 'SUCCESS' ? 'ok' : 'bad'}>
                      {item.result === 'SUCCESS' ? '成功' : '失败'}
                    </StatusPill>
                    {item.error && <div className="fine-print">{item.error}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pager page={page} size={20} total={total} onChange={setPage} />
    </Card>
  );
}
