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
import { permissions } from '../auth/permissions';
import { ApiError } from '../api/client';

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

export function AlarmsPage({ api, role, grants }: { api: ApiClient; role: string; grants?: string[] }) {
  const [items, setItems] = useState<AlarmItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [level, setLevel] = useState('');
  const [status, setStatus] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const requestId = useRef(0);
  const ackPending=useRef(false),alive=useRef(true);
  const [from,setFrom]=useState(''),[to,setTo]=useState('');
  const [query,setQuery]=useState(''),[keyword,setKeyword]=useState('');
  const [notice,setNotice]=useState<string|null>(null);
  const canAcknowledge=permissions(role,grants).acknowledge;

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      if(from&&to&&from>to)throw new Error('开始时间不能晚于结束时间。');
      const result = await api.alarms({ page, size: 20, level, status, q:keyword,
        from:from?new Date(from).toISOString():undefined,to:to?new Date(to).toISOString():undefined });
      if (id !== requestId.current) return;
      setItems(result.items);
      setTotal(result.total);
      if(page>1&&result.items.length===0&&result.total<=(page-1)*20)setPage(Math.max(1,Math.ceil(result.total/20)));
    } catch (caught) {
      if (id === requestId.current) setError(errorMessage(caught));
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [api, level, page, status, from, to, keyword]);

  useEffect(() => {
    alive.current=true;
    void load();
    const timer=window.setInterval(()=>void load(),3000);
    return()=>{alive.current=false;++requestId.current;window.clearInterval(timer);};
  }, [load]);

  async function acknowledge(item: AlarmItem) {
    if(!canAcknowledge||ackPending.current||item.status==='ACKNOWLEDGED')return;
    ackPending.current=true;
    setBusyId(item.id);
    setError(null);
    setNotice(null);
    try {
      const acknowledged=await api.acknowledgeAlarm(item.id);
      if(!alive.current)return;
      ++requestId.current;
      setItems(old=>old.map(row=>row.id===item.id?acknowledged:row));
      setNotice(`告警 #${item.id} 已确认。故障状态请以实时数据为准。`);
      await load();
    } catch (caught) {
      if(!alive.current)return;
      if(caught instanceof ApiError&&caught.status===409){
        setNotice('该告警已被其他值班员确认，正在同步最新记录。');await load();
      }else setError(errorMessage(caught));
    } finally {
      ackPending.current=false;if(alive.current)setBusyId(null);
    }
  }

  return (
    <Card
      title="告警记录"
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
      <div className="data-toolbar">
        <form className="search-form" onSubmit={e=>{e.preventDefault();setPage(1);setKeyword(query.trim());}}>
          <input aria-label="搜索告警" placeholder="搜索告警、设备或描述" value={query} maxLength={80} onChange={e=>setQuery(e.target.value)}/>
          <button type="submit" className="button small">搜索</button>
        </form>
        <label>从<input aria-label="开始时间" type="datetime-local" value={from} onChange={e=>{setFrom(e.target.value);setPage(1);}}/></label>
        <label>至<input aria-label="结束时间" type="datetime-local" value={to} onChange={e=>{setTo(e.target.value);setPage(1);}}/></label>
        <span className="fine-print">{total} 条 · 3秒同步</span>
      </div>
      {notice&&<p className="notice notice-info" role="status">{notice}</p>}
      {error && <ErrorNotice message={error} onRetry={() => void load()} />}
      {loading && items.length === 0 ? (
        <Empty>正在加载告警…</Empty>
      ) : items.length === 0 ? (
        <Empty>没有符合条件的告警。</Empty>
      ) : (
        <div className="table-wrap"><table className="data-table alarm-table">
          <thead><tr><th>时间</th><th>级别</th><th>告警内容</th><th>设备 / 来源</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>{items.map(item=><tr key={item.id}>
            <td className="nowrap">{formatDateTime(item.occurredAt)}</td>
            <td><StatusPill tone={alarmTone(item.level)}>{alarmLevelLabel(item.level)}</StatusPill></td>
            <td className="alarm-title-cell"><strong>{item.title}</strong>
              <details><summary>查看详情</summary><p>{item.description??'—'}</p><p>建议：{alarmAdvice(item.source)}</p>
                {item.status==='ACKNOWLEDGED'&&<p>确认人 #{item.acknowledgedBy} · {formatDateTime(item.acknowledgedAt)}</p>}
              </details></td>
            <td className="nowrap">{item.source}</td>
            <td><StatusPill tone={item.status==='ACKNOWLEDGED'?'ok':'warn'}>{item.status==='ACKNOWLEDGED'?'已确认':'未确认'}</StatusPill></td>
            <td>{canAcknowledge&&item.status==='UNACKNOWLEDGED'?<button type="button" className="button small" disabled={busyId!==null} onClick={()=>void acknowledge(item)}>{busyId===item.id?'确认中…':'确认'}</button>:<span className="fine-print">—</span>}</td>
          </tr>)}</tbody>
        </table></div>
      )}
      <Pager page={page} size={20} total={total} onChange={setPage} />
    </Card>
  );
}

function alarmAdvice(source:string):string {
  if(source.endsWith(':NAV_OBSTACLE'))return '核对实时画面及激光；需要时立即停止，由现场人员确认通路。障碍消失后仍由操作员决定是否重新导航。';
  if(source.endsWith(':NAVIGATION'))return '检查导航停止原因、定位和传感器；排除问题后由操作员重新提交目标。';
  if(source==='DEMO_FIXTURE')return '这是演示记录，不代表实际设备异常。';
  if(source==='MQTT')return '检查服务器与 MQTT 连接，确认小车已安全停止。';
  return '根据告警详情检查设备和现场，记录已知悉后确认；设备恢复以实时状态为准。';
}

function alarmTone(level: string): 'bad' | 'warn' | 'neutral' {
  if (level === 'CRITICAL') return 'bad';
  if (level === 'WARNING') return 'warn';
  return 'neutral';
}
