export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date);
}

export function formatRelative(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (seconds < 5) return '刚刚';
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
  return formatDateTime(value);
}

export function formatNumber(
  value: number | null | undefined,
  digits = 2,
  unit = '',
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const text = value.toFixed(digits);
  return unit ? `${text} ${unit}` : text;
}

export function motionLabel(state: string): string {
  const labels: Record<string, string> = {
    STOPPED: '停止',
    FORWARD: '前进',
    BACKWARD: '后退',
    LEFT: '左转',
    RIGHT: '右转',
    UNKNOWN: '未知',
  };
  return labels[state] ?? state;
}

export function roleLabel(role: string): string {
  const labels: Record<string, string> = {
    OPERATOR: '操作员',
    MONITOR: '监控员',
    MAINTAINER: '维护员',
    VIEWER: '观察员',
  };
  return labels[role] ?? role;
}

export function taskStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    PLANNED: '已计划',
    CANCELLED: '已取消',
  };
  return labels[status] ?? status;
}

export function alarmLevelLabel(level: string): string {
  const labels: Record<string, string> = {
    INFO: '提示',
    WARNING: '警告',
    CRITICAL: '严重',
  };
  return labels[level] ?? level;
}

export function operationLabel(operation: string): string {
  const labels: Record<string, string> = {
    LOGIN: '登录',
    REGISTER: '注册',
    ROBOT_CONTROL: '机器人控制',
    ROUTE_CREATE: '创建路线',
    ROUTE_UPDATE: '修改路线',
    ROUTE_DELETE: '删除路线',
    TASK_CREATE: '创建任务',
    TASK_UPDATE: '修改任务',
    TASK_DELETE: '删除任务',
    ALARM_ACK: '确认告警',
    AUTH_FAILURE: '认证失败',
    BUSINESS_FAILURE: '业务失败',
    DEMO_INIT: '演示初始化',
  };
  return labels[operation] ?? operation;
}

export function toDateTimeLocal(value: string | null): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function fromDateTimeLocal(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return date.toISOString();
}
