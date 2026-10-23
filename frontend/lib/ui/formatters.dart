/// Small presentation helpers shared by the pages.
library;

String twoDigits(int value) => value.toString().padLeft(2, '0');

String formatDateTime(DateTime? value) {
  if (value == null) return '—';
  final local = value.toLocal();
  return '${local.year}-${twoDigits(local.month)}-${twoDigits(local.day)} '
      '${twoDigits(local.hour)}:${twoDigits(local.minute)}:${twoDigits(local.second)}';
}

String formatRelative(DateTime? value) {
  if (value == null) return '—';
  final diff = DateTime.now().difference(value.toLocal());
  if (diff.isNegative || diff.inSeconds < 5) return '刚刚';
  if (diff.inSeconds < 60) return '${diff.inSeconds} 秒前';
  if (diff.inMinutes < 60) return '${diff.inMinutes} 分钟前';
  if (diff.inHours < 24) return '${diff.inHours} 小时前';
  return formatDateTime(value);
}

String formatNumber(double? value, {int digits = 2, String unit = ''}) {
  if (value == null) return '—';
  final text = value.toStringAsFixed(digits);
  return unit.isEmpty ? text : '$text $unit';
}

String formatCoordinate(double? value) => formatNumber(value, digits: 2);

String motionLabel(String state) => switch (state) {
  'STOPPED' => '停止',
  'FORWARD' => '前进',
  'BACKWARD' => '后退',
  'LEFT' => '左转',
  'RIGHT' => '右转',
  'UNKNOWN' => '未知',
  _ => state,
};

String roleLabel(String role) => switch (role) {
  'OPERATOR' => '操作员',
  'MONITOR' => '监控员',
  'MAINTAINER' => '维护员',
  'VIEWER' => '观察员',
  _ => role,
};

String levelLabel(String level) => switch (level) {
  'INFO' => '提示',
  'WARNING' => '警告',
  'CRITICAL' => '严重',
  _ => level,
};

String alarmStatusLabel(String status) =>
    status == 'ACKNOWLEDGED' ? '已确认' : '未确认';

String ackResultLabel(String result) => switch (result) {
  'APPLIED' => '已执行',
  'REJECTED' => '已拒绝',
  _ => result,
};

String logResultLabel(String result) => result == 'SUCCESS' ? '成功' : '失败';

String operationLabel(String operation) => switch (operation) {
  'LOGIN' => '登录',
  'REGISTER' => '注册',
  'ROBOT_CONTROL' => '机器人控制',
  'ROUTE_CREATE' => '创建路线',
  'ROUTE_UPDATE' => '修改路线',
  'ROUTE_DELETE' => '删除路线',
  'TASK_CREATE' => '创建任务',
  'TASK_UPDATE' => '修改任务',
  'TASK_DELETE' => '删除任务',
  'ALARM_ACK' => '确认告警',
  'AUTH_FAILURE' => '认证失败',
  'BUSINESS_FAILURE' => '业务失败',
  'DEMO_INIT' => '演示初始化',
  _ => operation,
};
