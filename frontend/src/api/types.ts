export type Role = 'OPERATOR' | 'MONITOR' | 'MAINTAINER' | 'VIEWER' | string;

export interface NavigationStatus {
  enabled: boolean;
  ready: boolean;
  reason: string | null;
  active: boolean;
  missionId: string | null;
  ownerId: number | null;
  robot: null | {
    mode?: 'map' | 'relative';
    ready: boolean; reason: string | null; mapId: string | null; frame: string;
    state: string; missionId: string | null; pointIndex: number; totalPoints: number;
    x: number | null; y: number | null; yaw: number | null;
  };
}

export interface UserView {
  id: number;
  username: string;
  role: Role;
  createdAt?: string | null;
}

export interface LoginResult {
  token: string;
  user: UserView;
}

export interface RobotInfo {
  robotId: string;
  name: string;
  model: string;
  commands: string[];
  online: boolean;
  mqttConnected: boolean;
  lastSeenAt: string | null;
}

export interface Position {
  x: number | null;
  y: number | null;
  yaw: number | null;
}

export interface AckInfo {
  seq: number;
  command: string;
  result: 'APPLIED' | 'REJECTED' | string;
  reason: string | null;
  receivedAt: string | null;
}

export interface RobotStatus {
  robotId: string;
  online: boolean;
  motionState: 'STOPPED' | 'FORWARD' | 'BACKWARD' | 'LEFT' | 'RIGHT' | 'UNKNOWN' | string;
  batteryPct: number | null;
  batteryVoltage: number | null;
  linearVelocity: number | null;
  angularVelocity: number | null;
  position: Position | null;
  mqttConnected: boolean;
  chassisConnected: boolean | null;
  lastSeenAt: string | null;
  telemetryAt: string | null;
  lastAck: AckInfo | null;
}

export interface ControlResult {
  accepted: boolean;
  command: string;
  seq: number;
  timestamp: string | null;
}

export interface AlarmItem {
  id: number;
  title: string;
  description: string | null;
  level: 'INFO' | 'WARNING' | 'CRITICAL' | string;
  status: 'UNACKNOWLEDGED' | 'ACKNOWLEDGED' | string;
  source: string;
  occurredAt: string | null;
  acknowledgedBy: number | null;
  acknowledgedAt: string | null;
}

export interface ControlLogItem {
  id: number;
  actorId: number | null;
  actorName: string;
  operation: string;
  target: string;
  result: 'SUCCESS' | 'FAILURE' | string;
  occurredAt: string | null;
  error: string | null;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

export interface RoutePoint {
  sequence: number;
  x: number;
  y: number;
  yaw: number | null;
}

export interface PatrolRoute {
  id: number;
  name: string;
  description: string | null;
  points: RoutePoint[];
  createdAt: string | null;
  updatedAt: string | null;
}

export interface RouteInput {
  name: string;
  description?: string | null;
  points: RoutePoint[];
}

export interface PatrolTask {
  id: number;
  name: string;
  routeId: number;
  creatorId: number;
  plannedAt: string | null;
  status: 'DRAFT' | 'PLANNED' | 'CANCELLED' | string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface TaskInput {
  name: string;
  routeId: number;
  plannedAt?: string | null;
  status: 'DRAFT' | 'PLANNED' | 'CANCELLED' | string;
}
