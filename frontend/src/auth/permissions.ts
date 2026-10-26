// SRS v3.0 §§3.2, A7.6. Roles come from the authenticated server response.
export const CAPABILITIES = [
  {id:'MOVE',label:'手动驾驶'},
  {id:'STOP',label:'紧急停止 / 取消导航'},
  {id:'MAP_EDIT',label:'建图与定位'},
  {id:'NAVIGATE',label:'启动导航'},
  {id:'ALARM_ACK',label:'确认告警'},
  {id:'ALL_LOGS',label:'查看全部日志'},
  {id:'PATROL_EDIT',label:'路线任务管理'},
] as const;

export const ROLE_DEFAULTS: Record<string,string[]> = {
  OPERATOR:['MOVE','STOP','MAP_EDIT','NAVIGATE','PATROL_EDIT'],
  MONITOR:['STOP','ALARM_ACK'],
  MAINTAINER:['ALL_LOGS'], VIEWER:['ALL_LOGS'],
  ADMIN:CAPABILITIES.map(x=>x.id),
};

export function permissions(role: string, grants?: string[]) {
  const allowed = new Set(grants ?? ROLE_DEFAULTS[role] ?? []);
  return {
    move: allowed.has('MOVE'),
    stop: allowed.has('STOP'),
    mapEdit: allowed.has('MAP_EDIT'),
    navigate: allowed.has('NAVIGATE'),
    acknowledge: allowed.has('ALARM_ACK'),
    allLogs: allowed.has('ALL_LOGS'),
    patrolEdit: allowed.has('PATROL_EDIT'),
    manageUsers: role==='ADMIN',
  };
}

export function roleDescription(role: string): string {
  switch (role) {
    case 'ADMIN': return '系统管理员';
    case 'OPERATOR': return '可遥控、建图与导航；可查看告警和本人日志，告警由安全监控值班员确认。';
    case 'MONITOR': return '可监控、紧急停止、取消导航和确认告警；可查询本人操作日志，不能启动运动。';
    case 'MAINTAINER': return '可查看设备、地图、视频、全部告警与全部日志；不能控制小车或确认告警。';
    case 'VIEWER': return '只读查看设备、地图、视频、历史告警与全部日志，不能执行控制或修改。';
    default: return '当前角色没有控制或修改权限。';
  }
}
