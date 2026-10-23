import { useCallback, useEffect, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { PatrolRoute, PatrolTask } from '../api/types';
import {
  Card,
  Empty,
  ErrorNotice,
  Pager,
  StatusPill,
  errorMessage,
} from '../components/UI';
import { formatDateTime, taskStatusLabel } from '../utils/format';
import { RouteDialog } from './RouteDialog';
import { TaskDialog } from './TaskDialog';

type DialogState =
  | { type: 'route'; initial?: PatrolRoute }
  | { type: 'task'; initial?: PatrolTask }
  | null;

export function InspectionPage({ api, role }: { api: ApiClient; role: string }) {
  const [tab, setTab] = useState<'routes' | 'tasks'>('routes');
  const [routes, setRoutes] = useState<PatrolRoute[]>([]);
  const [routeTotal, setRouteTotal] = useState(0);
  const [routePage, setRoutePage] = useState(1);
  const [tasks, setTasks] = useState<PatrolTask[]>([]);
  const [taskTotal, setTaskTotal] = useState(0);
  const [taskPage, setTaskPage] = useState(1);
  const [allRoutes, setAllRoutes] = useState<PatrolRoute[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [reload, setReload] = useState(0);
  const canEdit = role === 'OPERATOR';

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([
      api.routes({ page: routePage, size: 20 }),
      api.routes({ page: 1, size: 100 }),
    ])
      .then(([pageResult, all]) => {
        if (cancelled) return;
        setRoutes(pageResult.items);
        setRouteTotal(pageResult.total);
        setAllRoutes(all.items);
      })
      .catch((caught) => {
        if (!cancelled) setError(errorMessage(caught));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [api, routePage, reload]);

  useEffect(() => {
    if (tab !== 'tasks') return undefined;
    let cancelled = false;
    setLoading(true);
    setError(null);
    api
      .tasks({ page: taskPage, size: 20 })
      .then((result) => {
        if (cancelled) return;
        setTasks(result.items);
        setTaskTotal(result.total);
      })
      .catch((caught) => {
        if (!cancelled) setError(errorMessage(caught));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [api, taskPage, reload, tab]);

  const refresh = useCallback(() => {
    setReload((value) => value + 1);
  }, []);

  async function deleteRoute(route: PatrolRoute) {
    if (!window.confirm(`确认删除路线“${route.name}”？被任务引用时后端会拒绝。`)) return;
    setError(null);
    try {
      await api.deleteRoute(route.id);
      setNotice('路线已删除。');
      refresh();
    } catch (caught) {
      setError(errorMessage(caught));
    }
  }

  async function deleteTask(task: PatrolTask) {
    if (!window.confirm(`确认删除任务“${task.name}”？`)) return;
    setError(null);
    try {
      await api.deleteTask(task.id);
      setNotice('任务已删除。');
      refresh();
    } catch (caught) {
      setError(errorMessage(caught));
    }
  }

  const routeNames = new Map(allRoutes.map((route) => [route.id, route.name]));

  return (
    <div className="stack">
      <div className="toolbar">
        <div className="segmented" role="tablist" aria-label="巡检视图">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'routes'}
            className={tab === 'routes' ? 'active' : ''}
            onClick={() => setTab('routes')}
          >
            路线
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'tasks'}
            className={tab === 'tasks' ? 'active' : ''}
            onClick={() => setTab('tasks')}
          >
            任务
          </button>
        </div>
        <StatusPill tone="neutral">
          {canEdit ? '操作员可编辑' : '只读视图'}
        </StatusPill>
      </div>

      {notice && <div className="notice notice-info">{notice}</div>}
      {error && <ErrorNotice message={error} onRetry={refresh} />}

      {tab === 'routes' ? (
        <Card
          title="路线"
          actions={
            canEdit && (
              <button
                type="button"
                className="button primary small"
                onClick={() => setDialog({ type: 'route' })}
              >
                + 新建路线
              </button>
            )
          }
        >
          {loading && routes.length === 0 ? (
            <Empty>正在加载路线…</Empty>
          ) : routes.length === 0 ? (
            <Empty>还没有路线。{canEdit ? '先新建一条路线。' : '请联系操作员创建。'}</Empty>
          ) : (
            <div className="list">
              {routes.map((route) => (
                <article className="list-item" key={route.id}>
                  <div className="list-main">
                    <div className="pill-row">
                      <StatusPill tone="neutral">{route.points.length} 个点位</StatusPill>
                      <span className="fine-print">更新 {formatDateTime(route.updatedAt)}</span>
                    </div>
                    <h3>{route.name}</h3>
                    <p className="muted">{route.description ?? '—'}</p>
                    <p className="route-points">
                      {route.points
                        .map((point) => `(${point.x}, ${point.y})`)
                        .join(' → ') || '—'}
                    </p>
                  </div>
                  {canEdit && (
                    <div className="row-actions">
                      <button
                        type="button"
                        className="button ghost small"
                        onClick={() => setDialog({ type: 'route', initial: route })}
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        className="button danger small"
                        onClick={() => void deleteRoute(route)}
                      >
                        删除
                      </button>
                    </div>
                  )}
                </article>
              ))}
            </div>
          )}
          <Pager page={routePage} size={20} total={routeTotal} onChange={setRoutePage} />
        </Card>
      ) : (
        <Card
          title="任务"
          actions={
            canEdit && (
              <button
                type="button"
                className="button primary small"
                disabled={allRoutes.length === 0}
                title={allRoutes.length === 0 ? '请先创建路线' : undefined}
                onClick={() => setDialog({ type: 'task' })}
              >
                + 新建任务
              </button>
            )
          }
        >
          {allRoutes.length === 0 && (
            <div className="notice notice-info">请先在“路线”中创建至少一条路线。</div>
          )}
          {loading && tasks.length === 0 ? (
            <Empty>正在加载任务…</Empty>
          ) : tasks.length === 0 ? (
            <Empty>还没有任务。</Empty>
          ) : (
            <div className="list">
              {tasks.map((task) => (
                <article className="list-item" key={task.id}>
                  <div className="list-main">
                    <div className="pill-row">
                      <StatusPill tone={task.status === 'PLANNED' ? 'ok' : 'neutral'}>
                        {taskStatusLabel(task.status)}
                      </StatusPill>
                      <span className="fine-print">
                        路线：{routeNames.get(task.routeId) ?? `#${task.routeId}`}
                      </span>
                    </div>
                    <h3>{task.name}</h3>
                    <p className="muted">
                      计划时间：{task.plannedAt ? formatDateTime(task.plannedAt) : '未设置'}
                    </p>
                  </div>
                  {canEdit && task.status !== 'CANCELLED' && (
                    <div className="row-actions">
                      <button
                        type="button"
                        className="button ghost small"
                        onClick={() => setDialog({ type: 'task', initial: task })}
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        className="button danger small"
                        onClick={() => void deleteTask(task)}
                      >
                        删除
                      </button>
                    </div>
                  )}
                </article>
              ))}
            </div>
          )}
          <Pager page={taskPage} size={20} total={taskTotal} onChange={setTaskPage} />
        </Card>
      )}

      {dialog?.type === 'route' && (
        <RouteDialog
          api={api}
          initial={dialog.initial}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            setNotice(dialog.initial ? '路线已更新。' : '路线已创建。');
            refresh();
          }}
        />
      )}
      {dialog?.type === 'task' && (
        <TaskDialog
          api={api}
          routes={allRoutes}
          initial={dialog.initial}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            setNotice(dialog.initial ? '任务已更新。' : '任务已创建。');
            refresh();
          }}
        />
      )}
    </div>
  );
}
