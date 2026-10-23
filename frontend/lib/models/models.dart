/// Data models of the exp3 backend REST API.
///
/// The backend uses camelCase JSON and a `{code, message, data}` envelope; every
/// field that the robot cannot measure is `null` and is kept `null` here. No
/// defaults are invented for telemetry.
library;

DateTime? apiTime(Object? value) {
  if (value is! String || value.isEmpty) return null;
  return DateTime.tryParse(value)?.toLocal();
}

double? apiDouble(Object? value) {
  if (value is num && value.isFinite) return value.toDouble();
  return null;
}

int? apiInt(Object? value) {
  if (value is num) return value.toInt();
  return null;
}

bool? apiBool(Object? value) {
  if (value is bool) return value;
  return null;
}

class UserView {
  const UserView({
    required this.id,
    required this.username,
    required this.role,
    this.createdAt,
  });

  final int id;
  final String username;
  final String role;
  final DateTime? createdAt;

  bool get canControlMotion => role == 'OPERATOR';
  bool get canStop => role == 'OPERATOR' || role == 'MONITOR';

  factory UserView.fromJson(Map<String, dynamic> json) => UserView(
    id: apiInt(json['id']) ?? 0,
    username: json['username'] as String? ?? '',
    role: json['role'] as String? ?? 'VIEWER',
    createdAt: apiTime(json['createdAt']),
  );
}

class LoginResult {
  const LoginResult({required this.token, required this.user});

  final String token;
  final UserView user;
}

class RobotInfo {
  const RobotInfo({
    required this.robotId,
    required this.name,
    required this.model,
    required this.commands,
    required this.online,
    required this.mqttConnected,
    this.lastSeenAt,
  });

  final String robotId;
  final String name;
  final String model;
  final List<String> commands;
  final bool online;
  final bool mqttConnected;
  final DateTime? lastSeenAt;

  factory RobotInfo.fromJson(Map<String, dynamic> json) => RobotInfo(
    robotId: json['robotId'] as String? ?? '',
    name: json['name'] as String? ?? '',
    model: json['model'] as String? ?? '',
    commands:
        (json['commands'] as List<dynamic>?)?.whereType<String>().toList(
          growable: false,
        ) ??
        const <String>[],
    online: json['online'] == true,
    mqttConnected: json['mqttConnected'] == true,
    lastSeenAt: apiTime(json['lastSeenAt']),
  );
}

class Position {
  const Position({this.x, this.y, this.yaw});

  final double? x;
  final double? y;
  final double? yaw;

  factory Position.fromJson(Map<String, dynamic> json) => Position(
    x: apiDouble(json['x']),
    y: apiDouble(json['y']),
    yaw: apiDouble(json['yaw']),
  );
}

class AckInfo {
  const AckInfo({
    required this.seq,
    required this.command,
    required this.result,
    this.reason,
    this.receivedAt,
  });

  final int seq;
  final String command;
  final String result;
  final String? reason;
  final DateTime? receivedAt;

  factory AckInfo.fromJson(Map<String, dynamic> json) => AckInfo(
    seq: apiInt(json['seq']) ?? 0,
    command: json['command'] as String? ?? 'UNKNOWN',
    result: json['result'] as String? ?? 'UNKNOWN',
    reason: json['reason'] as String?,
    receivedAt: apiTime(json['receivedAt']),
  );
}

class RobotStatus {
  const RobotStatus({
    required this.robotId,
    required this.online,
    required this.motionState,
    this.batteryPct,
    this.batteryVoltage,
    this.linearVelocity,
    this.angularVelocity,
    this.position,
    required this.mqttConnected,
    this.chassisConnected,
    this.lastSeenAt,
    this.telemetryAt,
    this.lastAck,
  });

  final String robotId;
  final bool online;
  final String motionState;
  final double? batteryPct;
  final double? batteryVoltage;
  final double? linearVelocity;
  final double? angularVelocity;
  final Position? position;
  final bool mqttConnected;
  final bool? chassisConnected;
  final DateTime? lastSeenAt;
  final DateTime? telemetryAt;
  final AckInfo? lastAck;

  factory RobotStatus.fromJson(Map<String, dynamic> json) {
    final position = json['position'];
    final ack = json['lastAck'];
    return RobotStatus(
      robotId: json['robotId'] as String? ?? '',
      online: json['online'] == true,
      motionState: json['motionState'] as String? ?? 'UNKNOWN',
      batteryPct: apiDouble(json['batteryPct']),
      batteryVoltage: apiDouble(json['batteryVoltage']),
      linearVelocity: apiDouble(json['linearVelocity']),
      angularVelocity: apiDouble(json['angularVelocity']),
      position: position is Map<String, dynamic>
          ? Position.fromJson(position)
          : null,
      mqttConnected: json['mqttConnected'] == true,
      chassisConnected: apiBool(json['chassisConnected']),
      lastSeenAt: apiTime(json['lastSeenAt']),
      telemetryAt: apiTime(json['telemetryAt']),
      lastAck: ack is Map<String, dynamic> ? AckInfo.fromJson(ack) : null,
    );
  }
}

class ControlResult {
  const ControlResult({
    required this.accepted,
    required this.command,
    required this.seq,
    this.timestamp,
  });

  final bool accepted;
  final String command;
  final int seq;
  final DateTime? timestamp;

  factory ControlResult.fromJson(Map<String, dynamic> json) => ControlResult(
    accepted: json['accepted'] == true,
    command: json['command'] as String? ?? '',
    seq: apiInt(json['seq']) ?? 0,
    timestamp: apiTime(json['timestamp']),
  );
}

class AlarmItem {
  const AlarmItem({
    required this.id,
    required this.title,
    required this.level,
    required this.status,
    required this.source,
    this.description,
    this.occurredAt,
    this.acknowledgedBy,
    this.acknowledgedAt,
  });

  final int id;
  final String title;
  final String? description;
  final String level;
  final String status;
  final String source;
  final DateTime? occurredAt;
  final int? acknowledgedBy;
  final DateTime? acknowledgedAt;

  bool get unacknowledged => status == 'UNACKNOWLEDGED';

  factory AlarmItem.fromJson(Map<String, dynamic> json) => AlarmItem(
    id: apiInt(json['id']) ?? 0,
    title: json['title'] as String? ?? '',
    description: json['description'] as String?,
    level: json['level'] as String? ?? 'INFO',
    status: json['status'] as String? ?? 'UNACKNOWLEDGED',
    source: json['source'] as String? ?? '',
    occurredAt: apiTime(json['occurredAt']),
    acknowledgedBy: apiInt(json['acknowledgedBy']),
    acknowledgedAt: apiTime(json['acknowledgedAt']),
  );
}

class ControlLogItem {
  const ControlLogItem({
    required this.id,
    required this.actorName,
    required this.operation,
    required this.target,
    required this.result,
    this.actorId,
    this.error,
    this.occurredAt,
  });

  final int id;
  final int? actorId;
  final String actorName;
  final String operation;
  final String target;
  final String result;
  final String? error;
  final DateTime? occurredAt;

  bool get failed => result == 'FAILURE';

  factory ControlLogItem.fromJson(Map<String, dynamic> json) => ControlLogItem(
    id: apiInt(json['id']) ?? 0,
    actorId: apiInt(json['actorId']),
    actorName: json['actorName'] as String? ?? '',
    operation: json['operation'] as String? ?? '',
    target: json['target'] as String? ?? '',
    result: json['result'] as String? ?? '',
    error: json['error'] as String?,
    occurredAt: apiTime(json['occurredAt']),
  );
}

class PageResult<T> {
  const PageResult({
    required this.items,
    required this.total,
    required this.page,
    required this.size,
  });

  final List<T> items;
  final int total;
  final int page;
  final int size;

  bool get hasPrevious => page > 1;
  bool get hasNext => page * size < total;

  factory PageResult.fromJson(
    Map<String, dynamic> json,
    T Function(Map<String, dynamic>) itemFromJson,
  ) {
    final rawItems = json['items'];
    final items = rawItems is List<dynamic>
        ? rawItems
              .whereType<Map<String, dynamic>>()
              .map(itemFromJson)
              .toList(growable: false)
        : <T>[];
    return PageResult<T>(
      items: items,
      total: apiInt(json['total']) ?? 0,
      page: apiInt(json['page']) ?? 1,
      size: apiInt(json['size']) ?? 20,
    );
  }
}
