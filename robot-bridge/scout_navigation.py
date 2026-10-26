# -*- coding: utf-8 -*-
"""Optional ROS1 move_base adapter and fail-closed velocity arbiter.

move_base MUST be remapped to /navigation/cmd_vel. Only the existing bridge
publishes chassis /cmd_vel, so cloud STOP, lease loss and manual override win.
"""
import hashlib
import json
import math
import threading
import time
import os
import subprocess
import uuid
from scout_bridge_core import ConfigError

TERMINAL = {2: 'CANCELLED', 3: 'SUCCEEDED', 4: 'ABORTED', 5: 'REJECTED', 8: 'CANCELLED', 9: 'ABORTED'}
RELATIVE_MAX_DISTANCE = 3.0


def relative_goal(point, x, y, yaw):
    """Freeze a body-relative offset in odom once, never chase a moving goal."""
    distance = math.hypot(point['x'], point['y'])
    if not 0.1 <= distance <= RELATIVE_MAX_DISTANCE:
        raise ValueError('RELATIVE_GOAL_OUT_OF_RANGE')
    heading = yaw + point['yaw']
    return dict(x=x + math.cos(yaw)*point['x'] - math.sin(yaw)*point['y'],
                y=y + math.sin(yaw)*point['x'] + math.cos(yaw)*point['y'],
                yaw=math.atan2(math.sin(heading), math.cos(heading)))


def direct_velocity(x, y, yaw, goal):
    """Conservative odometry-only guidance for a *cleared, supervised* test area.

    This deliberately has no obstacle sensing.  Velocity only reaches the robot
    through the existing lease/STOP arbiter, never a direct /cmd_vel publisher.
    """
    distance = math.hypot(goal['x'] - x, goal['y'] - y)
    if distance <= 0.12:
        error = math.atan2(math.sin(goal['yaw'] - yaw), math.cos(goal['yaw'] - yaw))
        if abs(error) <= 0.15:
            return (0.0, 0.0, True)
        return (0.0, max(-0.25, min(0.25, 1.2 * error)), False)
    heading = math.atan2(goal['y'] - y, goal['x'] - x)
    error = math.atan2(math.sin(heading - yaw), math.cos(heading - yaw))
    angular = max(-0.25, min(0.25, 1.2 * error))
    if abs(error) > 0.35:
        return (0.0, angular, False)
    linear = min(0.10, max(0.025, 0.5 * distance)) * max(0.25, 1.0 - abs(error) / 0.5)
    return (linear, angular, False)


class NavigationManager:
    def __init__(self, config, adapter, send_velocity, clock=time.monotonic, wall=time.time):
        self.config = config
        self.adapter = adapter
        self.send = send_velocity
        self.clock, self.wall = clock, wall
        self.lock = threading.RLock()
        self.state = 'IDLE'
        self.reason = None
        self.mission = None
        self.points = []
        self.index = 0
        self.lease_at = 0
        self.goal_at = 0
        self.map_id = None
        self.map_snapshot = (None, None)
        self.map_lock = threading.Lock()
        self.map_generation = 0
        self.velocity = (0.0, 0.0)
        self.velocity_at = 0
        self.generation = 0
        self.relative_origin = self.relative_last = None
        self.relative_travel = 0

    def report(self):
        with self.lock:
            health = self.adapter.health()
            return dict(health, mode=self.config.get('mode', 'map'), state=self.state, reason=self.reason or health.get('reason'),
                        mission_id=self.mission, point_index=self.index,
                        total_points=len(self.points))

    def command(self, message):
        with self.lock:
            expires = message.get('expires_at')
            if isinstance(expires, bool) or not isinstance(expires, (int, float)) or not math.isfinite(expires):
                return
            # Reject delayed/replayed starts. Clock skew fails closed; synchronize NTP.
            if not self.wall() * 1000 <= expires <= self.wall() * 1000 + 10000:
                return
            order = message.get('order')
            if not isinstance(order, dict):
                return
            action = order.get('action')
            if action == 'CANCEL':
                self.cancel('CLOUD_CANCEL')
                return
            if action == 'KEEPALIVE':
                if order.get('missionId') == self.mission and self.state in ('ACTIVE', 'PENDING'):
                    if self.clock() - self.lease_at <= 3:
                        self.lease_at = self.clock()
                return
            if action != 'START' or self.state in ('ACTIVE', 'PENDING'):
                return
            requested_mission = order.get('missionId')
            if isinstance(requested_mission, str) and 1 <= len(requested_mission) <= 64:
                self.mission = requested_mission
            health = self.adapter.health()
            if not health.get('ready') or order.get('mapId') != health.get('map_id'):
                self.state, self.reason = 'REJECTED', 'NAVIGATION_NOT_READY_OR_MAP_CHANGED'
                return
            points, cycles, mission = order.get('points'), order.get('cycles'), order.get('missionId')
            mode = self.config.get('mode', 'map')
            if order.get('mode', 'map') != mode:
                self.state, self.reason = 'REJECTED', 'NAVIGATION_MODE_MISMATCH'
                return
            if (not isinstance(points, list) or not 1 <= len(points) <= 30
                    or isinstance(cycles, bool) or not isinstance(cycles, int) or not 1 <= cycles <= 20
                    or not isinstance(mission, str) or not 1 <= len(mission) <= 64):
                self.state, self.reason = 'REJECTED', 'INVALID_MISSION'
                return
            if mode == 'relative' and (len(points) != 1 or cycles != 1):
                self.state, self.reason = 'REJECTED', 'RELATIVE_SINGLE_GOAL_ONLY'
                return
            for p in points:
                if not isinstance(p, dict) or any(isinstance(p.get(k), bool) or not isinstance(p.get(k), (int, float))
                                                or not math.isfinite(p[k]) for k in ('x', 'y', 'yaw')):
                    self.state, self.reason = 'REJECTED', 'INVALID_GOAL'
                    return
                if abs(p['x']) > 10000 or abs(p['y']) > 10000 or abs(p['yaw']) > math.pi:
                    self.state, self.reason = 'REJECTED', 'INVALID_GOAL'
                    return
            if mode == 'relative':
                try:
                    points = self.adapter.prepare_points(points)
                except ValueError as error:
                    self.state, self.reason = 'REJECTED', str(error)
                    return
            for p in points:
                if not self.adapter.goal_allowed(p):
                    self.state, self.reason = 'REJECTED', 'GOAL_NOT_IN_FREE_MAP_SPACE'
                    return
            self.mission, self.map_id = mission, order['mapId']
            self.points, self.index = points * cycles, 0
            self.lease_at = self.clock()
            self.reason = None
            self.relative_origin = self.relative_last = (health.get('x'), health.get('y')) if mode == 'relative' else None
            self.relative_travel = 0
            self._next()

    def _next(self):
        self.generation += 1
        generation = self.generation
        self.state = 'ACTIVE'
        self.goal_at = self.clock()
        self.velocity = (0.0, 0.0)
        self.velocity_at = 0
        try:
            if self.config.get('direct'):
                # Each worker keeps its own callback. A cancelled worker must
                # never feed velocity into a later mission after START/STOP.
                self.adapter.velocity_callback = lambda linear, angular: self.accept_velocity(
                    linear, angular, generation)
            self.adapter.send_goal(self.points[self.index], lambda result: self.finished(result, generation))
        except Exception:
            self.cancel('GOAL_DELIVERY_FAILED')

    def finished(self, result, generation):
        with self.lock:
            if generation != self.generation or self.state != 'ACTIVE':
                return
            self.send(0.0, 0.0)
            if result == 3:
                self.index += 1
                if self.index < len(self.points):
                    self._next()
                else:
                    self.state, self.reason = 'SUCCEEDED', ('ODOM_GOAL_REACHED' if self.config.get('direct')
                                                            else 'MOVE_BASE_SUCCEEDED')
                    self.velocity = (0.0, 0.0)
            else:
                self.state = TERMINAL.get(result, 'ABORTED')
                self.reason = ('ODOM_RESULT_%s' if self.config.get('direct') else 'MOVE_BASE_RESULT_%s') % result
                self.velocity = (0.0, 0.0)

    def accept_velocity(self, linear, angular, generation=None):
        with self.lock:
            if self.state != 'ACTIVE' or (generation is not None and generation != self.generation):
                return
            if not math.isfinite(linear) or not math.isfinite(angular):
                self.cancel('INVALID_NAVIGATION_VELOCITY')
                return
            self.velocity = (max(-self.config['max_linear'], min(self.config['max_linear'], linear)),
                             max(-self.config['max_angular'], min(self.config['max_angular'], angular)))
            self.velocity_at = self.clock()

    def tick(self):
        with self.lock:
            if self.state != 'ACTIVE':
                return False
            health = self.adapter.health()
            if self.clock() - self.lease_at > 3:
                self.cancel('LEASE_EXPIRED')
            elif self.clock() - self.goal_at > self.config['goal_timeout']:
                self.cancel('GOAL_TIMEOUT')
            elif not health.get('ready') or health.get('map_id') != self.map_id:
                self.cancel('NAVIGATION_HEALTH_LOST')
            elif self.relative_origin is not None and not self._within_relative_bounds(health):
                self.cancel('RELATIVE_TRAVEL_LIMIT')
            else:
                self.send(*(self.velocity if self.clock() - self.velocity_at < 0.35 else (0.0, 0.0)))
            return True

    def _within_relative_bounds(self, health):
        current = health.get('x'), health.get('y')
        if any(v is None or not math.isfinite(v) for v in current + self.relative_origin):
            return False
        self.relative_travel += math.hypot(current[0]-self.relative_last[0], current[1]-self.relative_last[1])
        self.relative_last = current
        return (math.hypot(current[0]-self.relative_origin[0], current[1]-self.relative_origin[1]) <= 4
                and self.relative_travel <= 6)

    def cancel(self, reason):
        with self.lock:
            self.generation += 1
            if self.state in ('ACTIVE', 'PENDING'):
                self.state, self.reason = 'CANCELLED', reason
                self.send(0.0, 0.0)
                try:
                    self.adapter.cancel()
                except Exception:
                    # The velocity gate is already closed, even if actionlib is down.
                    pass
            self.velocity = (0.0, 0.0)

    def shutdown(self):
        self.cancel('BRIDGE_SHUTDOWN')
        close = getattr(self.adapter, 'close', None)
        if close:
            try:
                close()
            except Exception:
                import logging
                logging.getLogger(__name__).warning('Navigation pose relay cleanup unavailable')


class Ros1NavigationAdapter:
    def __init__(self, config, ros, telemetry):
        import actionlib
        import rospy
        from move_base_msgs.msg import MoveBaseAction, MoveBaseGoal
        from sensor_msgs.msg import LaserScan
        from nav_msgs.msg import OccupancyGrid
        from geometry_msgs.msg import Twist, PoseWithCovarianceStamped, PoseStamped
        self.rospy, self.config, self.telemetry = rospy, config, telemetry
        self.goal_type = MoveBaseGoal
        self.client = actionlib.SimpleActionClient(config['action'], MoveBaseAction)
        self.pose = None
        self.pose_at = 0
        self.map = None
        self.map_id = None
        self.confirmed_map = None
        self.scan_at = self.costmap_at = 0
        self.health_at = 0
        self.health_cache = {}
        self.velocity_callback = None
        rospy.Subscriber(config['map_topic'], OccupancyGrid, self._map, queue_size=1)
        rospy.Subscriber(config['scan_topic'], LaserScan, self._scan, queue_size=1)
        rospy.Subscriber(config['costmap_topic'], OccupancyGrid, self._costmap, queue_size=1)
        rospy.Subscriber(config['velocity_topic'], Twist, self._velocity, queue_size=1)
        rospy.Subscriber('/initialpose', PoseWithCovarianceStamped, self._initialpose, queue_size=1)
        rospy.Subscriber(config['pose_topic'], PoseStamped, self._pose, queue_size=1)
        self.ros = ros
        env = dict(os.environ)
        for key in ('PYTHONHOME', 'PATROL_MAPPING_DEVICE_KEY', 'PATROL_CAMERA_DEVICE_KEY', 'SCOUT_RTSP_PASSWORD'):
            env.pop(key, None)
        self.pose_process = subprocess.Popen([
            config['pose_python'], os.path.join(os.path.dirname(__file__), 'ros1_tf_pose.py'),
            '--frame', config['frame'], '--base', config['base_frame'], '--topic', config['pose_topic'],
            '--scan-topic', config['scan_topic']], env=env)
        # Master/action/TF checks may block. Keep them off the velocity/STOP thread.
        threading.Thread(target=self._check_loop, daemon=True).start()

    def _check_loop(self):
        while not self.rospy.is_shutdown():
            checked_at = time.monotonic()
            self.health_cache = self._check_health()
            self.health_at = checked_at
            time.sleep(0.2)

    def _map(self, msg):
        with self.map_lock:
            generation = self.map_generation
            web = getattr(self, 'web_mapping', None)
            if web and getattr(msg, '_connection_header', {}).get('callerid') != web.map_source:
                return
        if not msg.info.width or not msg.info.height or not math.isfinite(msg.info.resolution) or msg.info.resolution < 0.005:
            return
        if len(msg.data) != msg.info.width * msg.info.height or len(msg.data) > 16_000_000:
            return
        metadata = [msg.header.frame_id, msg.info.width, msg.info.height, msg.info.resolution,
                    msg.info.origin.position.x, msg.info.origin.position.y,
                    msg.info.origin.orientation.z, msg.info.origin.orientation.w]
        digest = hashlib.sha256(json.dumps(metadata).encode())
        digest.update(bytes(bytearray((v + 1) % 256 for v in msg.data)))
        with self.map_lock:
            if generation != self.map_generation:
                return
            self.map, self.map_id = msg, digest.hexdigest()
            self.map_snapshot = (msg, self.map_id)

    def _scan(self, msg):
        if msg.ranges and any(math.isfinite(v) and msg.range_min < v < msg.range_max for v in msg.ranges):
            if abs((self.rospy.Time.now() - msg.header.stamp).to_sec()) <= 2:
                self.scan_at = time.monotonic()

    def _costmap(self, msg):
        if msg.data and abs((self.rospy.Time.now() - msg.header.stamp).to_sec()) <= 3:
            self.costmap_at = time.monotonic()

    def _velocity(self, msg):
        if self.velocity_callback:
            self.velocity_callback(msg.linear.x, msg.angular.z)

    def _initialpose(self, msg):
        # A fresh RViz 2D Pose Estimate is an explicit operator localization step.
        if (self.map_id and msg.header.frame_id == self.config['frame']
                and abs((self.rospy.Time.now() - msg.header.stamp).to_sec()) <= 3):
            self.confirmed_map = self.map_id

    def _pose(self, msg):
        if msg.header.frame_id == self.config['frame']:
            self.pose, self.pose_at = msg, time.monotonic()

    def close(self):
        if self.pose_process.poll() is None:
            self.pose_process.terminate()
            try:
                self.pose_process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.pose_process.kill()
                self.pose_process.wait(timeout=3)

    def health(self):
        if time.monotonic() - self.health_at > 0.75:
            return dict(ready=False, reason='NAVIGATION_CHECK_STALE', map_id=self.map_id, frame=self.config['frame'])
        return dict(self.health_cache)

    def _check_health(self):
        now = time.monotonic()
        result = dict(ready=False, reason='MAP_UNAVAILABLE', map_id=self.map_id,
                      frame=self.config['frame'], x=None, y=None, yaw=None)
        try:
            web = getattr(self, 'web_mapping', None)
            if web and (web.busy or web.mode != 'NAVIGATION'):
                result['reason'] = 'WEB_MAPPING_ACTIVE' if web.mode == 'MAPPING' else 'WEB_MAP_NOT_LOADED'
                return result
            if self.map is None or self.map.header.frame_id != self.config['frame']:
                return result
            result['reason'] = 'INITIAL_POSE_REQUIRED'
            if self.config.get('mode', 'map') == 'map' and self.confirmed_map != self.map_id:
                return result
            result['reason'] = 'SENSOR_OR_COSTMAP_STALE'
            if now - self.scan_at > 1.5 or now - self.costmap_at > 3 or not self.telemetry.chassis_connected():
                return result
            result['reason'] = 'MOVE_BASE_UNAVAILABLE'
            if not self.client.wait_for_server(self.rospy.Duration(0.01)):
                return result
            result['reason'] = 'LOCALIZATION_UNAVAILABLE'
            pose = self.pose
            if self.pose_process.poll() is not None or pose is None or now - self.pose_at > 0.75:
                return result
            if abs((self.rospy.Time.now() - pose.header.stamp).to_sec()) > 1.5:
                return result
            q = pose.pose.orientation
            result.update(x=pose.pose.position.x, y=pose.pose.position.y,
                          yaw=math.atan2(2 * (q.w * q.z + q.x * q.y), 1 - 2 * (q.y*q.y + q.z*q.z)))
            result['reason'] = 'DIRECT_CMD_VEL_PUBLISHER'
            code, _, system = self.rospy.get_master().getSystemState()
            if code != 1:
                return result
            publishers = dict(system[0]).get(self.ros._publisher.resolved_name, [])
            if any(name != self.rospy.get_name() for name in publishers):
                return result
            result.update(ready=True, reason='READY')
            return result
        except Exception:
            return result

    def goal_allowed(self, point):
        grid = self.map
        if grid is None:
            return False
        origin = grid.info.origin
        yaw = math.atan2(2 * origin.orientation.w * origin.orientation.z, 1 - 2 * origin.orientation.z**2)
        dx, dy = point['x'] - origin.position.x, point['y'] - origin.position.y
        gx = int(math.floor((math.cos(yaw)*dx + math.sin(yaw)*dy) / grid.info.resolution))
        gy = int(math.floor((-math.sin(yaw)*dx + math.cos(yaw)*dy) / grid.info.resolution))
        # Installed footprint is +/-0.33m; use a conservative static goal margin.
        margin = int(math.ceil(0.35 / grid.info.resolution))
        if gx-margin < 0 or gy-margin < 0 or gx+margin >= grid.info.width or gy+margin >= grid.info.height:
            return False
        return all(0 <= grid.data[y*grid.info.width+x] <= 40
                   for y in range(gy-margin, gy+margin+1) for x in range(gx-margin, gx+margin+1))

    def send_goal(self, point, done):
        goal = self.goal_type()
        goal.target_pose.header.frame_id = self.config['frame']
        goal.target_pose.header.stamp = self.rospy.Time.now()
        goal.target_pose.pose.position.x, goal.target_pose.pose.position.y = point['x'], point['y']
        goal.target_pose.pose.orientation.z = math.sin(point['yaw'] / 2)
        goal.target_pose.pose.orientation.w = math.cos(point['yaw'] / 2)
        self.client.send_goal(goal, done_cb=lambda state, result: done(state))

    def cancel(self):
        self.client.cancel_all_goals()


class RelativeRos1NavigationAdapter(Ros1NavigationAdapter):
    """Rolling obstacle grid in odom; the reference ID identifies an odom session."""
    def __init__(self, config, ros, telemetry):
        self.reference_id = 'relative-' + uuid.uuid4().hex
        self.map_at = 0
        super().__init__(config, ros, telemetry)

    def _map(self, msg):
        if (msg.header.frame_id != 'odom' or not msg.info.width or not msg.info.height
                or not math.isfinite(msg.info.resolution) or msg.info.resolution < 0.005
                or len(msg.data) != msg.info.width * msg.info.height or len(msg.data) > 16_000_000
                or abs((self.rospy.Time.now() - msg.header.stamp).to_sec()) > 2):
            return
        # Obstacle updates and rolling origins are expected, not a new static map.
        self.map, self.map_id, self.map_at = msg, self.reference_id, time.monotonic()

    def _pose(self, msg):
        if msg.header.frame_id != 'odom':
            return
        if self.pose is not None:
            a, b = self.pose.pose, msg.pose
            delta = math.hypot(a.position.x-b.position.x, a.position.y-b.position.y)
            qa, qb = a.orientation, b.orientation
            ya = math.atan2(2*(qa.w*qa.z + qa.x*qa.y), 1-2*(qa.y*qa.y + qa.z*qa.z))
            yb = math.atan2(2*(qb.w*qb.z + qb.x*qb.y), 1-2*(qb.y*qb.y + qb.z*qb.z))
            if delta > 0.5 or abs(math.atan2(math.sin(ya-yb), math.cos(ya-yb))) > 0.6:
                self.reference_id = 'relative-' + uuid.uuid4().hex
                self.map_id, self.map, self.map_at = self.reference_id, None, 0
        super()._pose(msg)

    def _check_health(self):
        result = super()._check_health()
        if self.map is None or time.monotonic() - self.map_at > 2:
            result.update(ready=False, reason='LIVE_GRID_UNAVAILABLE')
        return result

    def prepare_points(self, points):
        # Use the bounded-age background result; never block STOP on ROS RPC.
        health = self.health()
        if (not health.get('ready') or health.get('map_id') != self.map_id
                or any(health.get(k) is None or not math.isfinite(health[k]) for k in ('x', 'y', 'yaw'))):
            raise ValueError('NAVIGATION_NOT_READY_OR_MAP_CHANGED')
        telemetry = self.telemetry.report()
        linear, angular = telemetry['linear_velocity'], telemetry['angular_velocity']
        if (linear is None or angular is None or not math.isfinite(linear) or not math.isfinite(angular)
                or abs(linear) > 0.03 or abs(angular) > 0.03):
            raise ValueError('ROBOT_MUST_BE_STOPPED')
        return [relative_goal(p, health['x'], health['y'], health['yaw']) for p in points]


class DirectOdomNavigationAdapter(RelativeRos1NavigationAdapter):
    """Explicit opt-in odometry-only navigation, without automatic avoidance.

    Intended solely for cleared, supervised test areas. The existing exclusive
    /cmd_vel publisher, MQTT lease, odometry health, distance/time limit and
    emergency STOP remain authoritative.
    """

    def __init__(self, config, ros, telemetry):
        self._direct_generation = 0
        super().__init__(config, ros, telemetry)
        self.reference_id = 'direct-' + uuid.uuid4().hex
        self.map_id = self.reference_id

    def _map(self, msg):
        # Lidar maps never enter the admission or control path in this mode.
        pass

    def _velocity(self, msg):
        # An old move_base /navigation/cmd_vel publisher must not steer the
        # odometry controller even if it survived the launcher transition.
        pass

    def _pose(self, msg):
        super()._pose(msg)
        # Invalidate the old mission when odometry jumps, without requiring a
        # fresh costmap to establish the new odometry session.
        if self.map_id is None or not self.reference_id.startswith('direct-'):
            self.reference_id = 'direct-' + uuid.uuid4().hex
            self.map_id = self.reference_id

    def _check_health(self):
        result = dict(ready=False, reason='LOCALIZATION_UNAVAILABLE', map_id=self.map_id,
                      frame='odom', x=None, y=None, yaw=None)
        try:
            if not self.telemetry.chassis_connected():
                result['reason'] = 'CHASSIS_UNAVAILABLE'
                return result
            pose = self.pose
            if (self.pose_process.poll() is not None or pose is None
                    or time.monotonic() - self.pose_at > 0.75
                    or pose.header.frame_id != 'odom'
                    or abs((self.rospy.Time.now() - pose.header.stamp).to_sec()) > 1.5):
                return result
            q = pose.pose.orientation
            yaw = math.atan2(2 * (q.w * q.z + q.x * q.y), 1 - 2 * (q.y*q.y + q.z*q.z))
            values = (pose.pose.position.x, pose.pose.position.y, yaw)
            if not all(math.isfinite(value) for value in values):
                return result
            result.update(x=values[0], y=values[1], yaw=values[2])
            result['reason'] = 'DIRECT_CMD_VEL_PUBLISHER'
            code, _, system = self.rospy.get_master().getSystemState()
            if code != 1:
                return result
            publishers = dict(system[0]).get(self.ros._publisher.resolved_name, [])
            if any(name != self.rospy.get_name() for name in publishers):
                return result
            result.update(ready=True, reason='READY')
            return result
        except Exception:
            return result

    def goal_allowed(self, point):
        # The operator, not radar, confirms the complete travel path is clear.
        return True

    def send_goal(self, point, done):
        self._direct_generation += 1
        generation = self._direct_generation
        velocity = self.velocity_callback  # Mission-scoped; do not read again in the worker.
        worker = threading.Thread(target=self._drive, args=(point, done, generation, velocity), daemon=True)
        worker.start()

    def _drive(self, point, done, generation, velocity):
        try:
            while not self.rospy.is_shutdown() and generation == self._direct_generation:
                health = self.health()
                if not health.get('ready') or health.get('map_id') != self.map_id:
                    velocity(0.0, 0.0)
                    if generation == self._direct_generation:
                        done(4)
                    return
                linear, angular, arrived = direct_velocity(health['x'], health['y'], health['yaw'], point)
                if arrived:
                    velocity(0.0, 0.0)
                    if generation == self._direct_generation:
                        done(3)
                    return
                velocity(linear, angular)
                time.sleep(0.1)
        except Exception:
            velocity(0.0, 0.0)
            if generation == self._direct_generation:
                done(4)

    def cancel(self):
        self._direct_generation += 1


def build_navigation(config, ros, telemetry, send):
    if not config['enabled']:
        return None
    if ros.name != 'ros1':
        raise ConfigError('Navigation currently supports ROS1 move_base only')
    adapter_type = (DirectOdomNavigationAdapter if config.get('direct') else
                    RelativeRos1NavigationAdapter if config.get('mode', 'map') == 'relative' else
                    Ros1NavigationAdapter)
    adapter = adapter_type(config, ros, telemetry)
    manager = NavigationManager(config, adapter, send)
    adapter.velocity_callback = manager.accept_velocity
    return manager
