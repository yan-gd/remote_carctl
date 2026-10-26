// SRS v3.0 §§3.2, A7.6. Roles come from the authenticated server response.
export function permissions(role: string) {
  return {
    move: role === 'OPERATOR',
    stop: role === 'OPERATOR' || role === 'MONITOR',
    acknowledge: role === 'MONITOR',
    allLogs: role === 'MAINTAINER' || role === 'VIEWER',
  };
}

export function roleDescription(role: string): string {
  switch (role) {
    case 'OPERATOR': return '可遥控、建图与导航；可查看告警和本人日志，告警由安全监控值班员确认。';
    case 'MONITOR': return '可监控、紧急停止、取消导航和确认告警；可查询本人操作日志，不能启动运动。';
    case 'MAINTAINER': return '可查看设备、地图、视频、全部告警与全部日志；不能控制小车或确认告警。';
    case 'VIEWER': return '只读查看设备、地图、视频、历史告警与全部日志，不能执行控制或修改。';
    default: return '当前角色没有控制或修改权限。';
  }
}
