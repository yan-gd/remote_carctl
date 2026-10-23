import 'models.dart';

/// Patrol route and task models (exp2 data management, unchanged by exp3).
///
/// Routes are data only: creating one never dispatches it to the robot.
class RoutePoint {
  const RoutePoint({
    required this.sequence,
    required this.x,
    required this.y,
    this.yaw,
  });

  final int sequence;
  final double x;
  final double y;
  final double? yaw;

  factory RoutePoint.fromJson(Map<String, dynamic> json) => RoutePoint(
    sequence: apiInt(json['sequence']) ?? 0,
    x: apiDouble(json['x']) ?? 0,
    y: apiDouble(json['y']) ?? 0,
    yaw: apiDouble(json['yaw']),
  );

  Map<String, dynamic> toJson() => <String, dynamic>{
    'sequence': sequence,
    'x': x,
    'y': y,
    if (yaw != null) 'yaw': yaw,
  };
}

class PatrolRoute {
  const PatrolRoute({
    required this.id,
    required this.name,
    required this.points,
    this.description,
    this.createdAt,
    this.updatedAt,
  });

  final int id;
  final String name;
  final String? description;
  final List<RoutePoint> points;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  factory PatrolRoute.fromJson(Map<String, dynamic> json) {
    final rawPoints = json['points'];
    return PatrolRoute(
      id: apiInt(json['id']) ?? 0,
      name: json['name'] as String? ?? '',
      description: json['description'] as String?,
      points: rawPoints is List<dynamic>
          ? rawPoints
                .whereType<Map<String, dynamic>>()
                .map(RoutePoint.fromJson)
                .toList(growable: false)
          : const <RoutePoint>[],
      createdAt: apiTime(json['createdAt']),
      updatedAt: apiTime(json['updatedAt']),
    );
  }
}

class RouteInput {
  const RouteInput({
    required this.name,
    required this.points,
    this.description,
  });

  final String name;
  final String? description;
  final List<RoutePoint> points;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'name': name,
    if (description != null && description!.trim().isNotEmpty)
      'description': description,
    'points': points.map((RoutePoint point) => point.toJson()).toList(),
  };
}

class PatrolTask {
  const PatrolTask({
    required this.id,
    required this.name,
    required this.routeId,
    required this.creatorId,
    required this.status,
    this.plannedAt,
    this.createdAt,
    this.updatedAt,
  });

  final int id;
  final String name;
  final int routeId;
  final int creatorId;
  final String status;
  final DateTime? plannedAt;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  bool get editable => status != 'CANCELLED';

  factory PatrolTask.fromJson(Map<String, dynamic> json) => PatrolTask(
    id: apiInt(json['id']) ?? 0,
    name: json['name'] as String? ?? '',
    routeId: apiInt(json['routeId']) ?? 0,
    creatorId: apiInt(json['creatorId']) ?? 0,
    status: json['status'] as String? ?? 'DRAFT',
    plannedAt: apiTime(json['plannedAt']),
    createdAt: apiTime(json['createdAt']),
    updatedAt: apiTime(json['updatedAt']),
  );
}

class TaskInput {
  const TaskInput({
    required this.name,
    required this.routeId,
    required this.status,
    this.plannedAt,
  });

  final String name;
  final int routeId;
  final String status;
  final DateTime? plannedAt;

  Map<String, dynamic> toJson() => <String, dynamic>{
    'name': name,
    'routeId': routeId,
    'status': status,
    if (plannedAt != null) 'plannedAt': plannedAt!.toUtc().toIso8601String(),
  };
}
