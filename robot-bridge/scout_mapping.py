# -*- coding: utf-8 -*-
"""Opt-in Web SLAM lifecycle. HTTP carries maps; MQTT remains the motion authority.

Runs inside the Python3 bridge, off its velocity and MQTT threads. Only launches
our fixed ROS launch files, never shell text or a path supplied by the browser.
"""
import base64
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
import time
import uuid
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.parse import urlsplit

NAME = re.compile(r'^[A-Za-z0-9_-]{1,40}$')
HERE = Path(__file__).resolve().parent


def validate_settings(server, key):
    url = urlsplit(server)
    if (url.scheme != 'https' and not (url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost'))
            or not url.hostname or url.path not in ('', '/') or url.username or url.password or url.query or url.fragment
            or not 32 <= len(key) <= 256):
        raise ValueError('Web mapping requires an HTTPS origin and a separate device key')


def map_display(grid, map_id, session):
    """Conservative reduced display only; navigation uses the original full grid."""
    w, h = grid.info.width, grid.info.height
    if not 0 < w*h <= 16000000 or len(grid.data) != w*h:
        raise ValueError('INVALID_MAP_SIZE')
    step = max(1, int(math.ceil(max(w, h)/512.0)))
    dw, dh = int(math.ceil(w/step)), int(math.ceil(h/step))
    out = bytearray(dw*dh)
    # Occupied wins, then unknown. Never turn an unseen cell into free space.
    for y in range(dh):
        for x in range(dw):
            unknown, occupied = (x+1)*step>w or (y+1)*step>h, False
            for yy in range(y*step, min((y+1)*step, h)):
                row = grid.data[yy*w+x*step:yy*w+min((x+1)*step, w)]
                if max(row) > 40:
                    occupied = True
                    break
                unknown = unknown or min(row) < 0
            out[y*dw+x] = 2 if occupied else 0 if unknown else 1
    p, q = grid.info.origin.position, grid.info.origin.orientation
    return dict(session=session, mapId=map_id, width=dw, height=dh,
                resolution=grid.info.resolution*step, originX=p.x, originY=p.y,
                originYaw=math.atan2(2*q.w*q.z, 1-2*q.z*q.z),
                cells=base64.b64encode(out).decode('ascii'))


def saved_map(root, name):
    if not isinstance(name, str) or not NAME.fullmatch(name):
        raise ValueError('INVALID_MAP_NAME')
    root = Path(root).resolve()
    folder = root/name
    if folder.is_symlink() or (folder.exists() and folder.resolve().parent != root):
        raise ValueError('INVALID_MAP_PATH')
    target = folder/'map.yaml'
    if target.is_symlink() or (folder/'map.pgm').is_symlink():
        raise ValueError('INVALID_MAP_PATH')
    return target


def save_grid(root, name, grid):
    import yaml
    target = saved_map(root, name)
    target.parent.mkdir()  # Exclusive: never overwrite a map, including incomplete saves.
    w, h = grid.info.width, grid.info.height
    if not 0 < w*h <= 16000000 or len(grid.data) != w*h:
        raise ValueError('INVALID_MAP_SIZE')
    image = target.parent/'map.pgm'
    with image.open('xb') as out:
        out.write(('P5\n%d %d\n255\n' % (w, h)).encode('ascii'))
        for y in range(h-1, -1, -1):
            out.write(bytes(254 if 0 <= v <= 25 else 0 if v >= 65 else 205
                            for v in grid.data[y*w:(y+1)*w]))
        out.flush()
        os.fsync(out.fileno())
    p, q = grid.info.origin.position, grid.info.origin.orientation
    meta = dict(image='map.pgm', resolution=grid.info.resolution,
                origin=[p.x, p.y, math.atan2(2*q.w*q.z, 1-2*q.z*q.z)],
                negate=0, occupied_thresh=0.65, free_thresh=0.196)
    temporary = target.parent/'map.yaml.tmp'
    with temporary.open('x') as out:
        yaml.safe_dump(meta, out, default_flow_style=False)
        out.flush()
        os.fsync(out.fileno())
    os.replace(str(temporary), str(target))  # The complete YAML is the commit marker.
    return target


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # Never forward a device credential to another origin.


class WebMapping:
    def __init__(self, core, adapter, server, key, root):
        validate_settings(server,key)
        self.core, self.adapter = core, adapter
        self.server, self.key = server.rstrip('/'), key
        self.root = Path(root).expanduser().resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.session = uuid.uuid4().hex
        self.mode, self.busy = 'IDLE', False
        self.map_source = None
        self.command_id = self.result = self.message = None
        self.process = None
        self.stop = threading.Event()
        self.lifecycle = threading.RLock()
        self.seen = set()
        self.scan, self.path = (None, 0), (None, 0)
        self.sent_grid = None
        self.opener = build_opener(NoRedirect())
        import rospy
        from geometry_msgs.msg import PoseWithCovarianceStamped
        from sensor_msgs.msg import PointCloud
        from nav_msgs.msg import Path as RosPath
        self.initial_type = PoseWithCovarianceStamped
        self.initial = rospy.Publisher('/initialpose', PoseWithCovarianceStamped, queue_size=1)
        rospy.Subscriber('/patrol/map_scan', PointCloud, lambda msg: setattr(self, 'scan', (msg, time.monotonic())), queue_size=1)
        for topic in ('/move_base/NavfnROS/plan', '/move_base/GlobalPlanner/plan', '/move_base/TrajectoryPlannerROS/global_plan'):
            rospy.Subscriber(topic, RosPath, lambda msg: setattr(self, 'path', (msg, time.monotonic())), queue_size=1)
        adapter.web_mapping = self

    def start(self):
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()
        # Encoding a large map or a slow image upload must not delay command receipts.
        threading.Thread(target=self._grid_loop, daemon=True).start()

    def _http(self, path, payload, method='POST'):
        data = json.dumps(payload, allow_nan=False).encode('utf-8')
        req = Request(self.server+'/api/v1/device/mapping/'+path, data=data, method=method,
                      headers={'Content-Type': 'application/json', 'X-Device-Key': self.key})
        with self.opener.open(req, timeout=3) as response:
            raw = response.read(16385)
            if len(raw) > 16384:
                raise ValueError('OVERSIZED_RESPONSE')
            return json.loads(raw.decode('utf-8'))['data']

    def _snapshot(self):
        return getattr(self.adapter, 'map_snapshot', (None, None))

    def _report(self):
        grid, map_id = self._snapshot()
        pose, now = self.adapter.pose, time.monotonic()
        measured = None
        if pose is not None and now-self.adapter.pose_at < 0.75 and self.mode in ('MAPPING', 'NAVIGATION'):
            p, q = pose.pose.position, pose.pose.orientation
            measured = dict(x=p.x, y=p.y, yaw=math.atan2(2*(q.w*q.z+q.x*q.y), 1-2*(q.y*q.y+q.z*q.z)))
        scan, at = self.scan
        dots = [dict(x=p.x, y=p.y) for p in scan.points[:720]] if scan is not None and now-at<1.5 and scan.header.frame_id=='map' else []
        path, at = self.path
        line = [dict(x=p.pose.position.x, y=p.pose.position.y) for p in path.poses[:1000]] if path is not None and now-at<3 and path.header.frame_id=='map' else []
        maps = sorted(p.name for p in self.root.iterdir() if NAME.fullmatch(p.name) and not p.is_symlink()
                      and saved_map(self.root, p.name).is_file())[:100]
        return dict(session=self.session, mode=self.mode, busy=self.busy, mapId=map_id,
                    maps=maps, pose=measured, scan=dots, path=line, commandId=self.command_id,
                    result=self.result, message=self.message)

    def _loop(self):
        while not self.stop.is_set():
            try:
                if self.process is not None and self.process.poll() is not None and not self.busy:
                    self._stop_pipeline()
                    self.mode, self.message = 'ERROR', 'ROS_PROCESS_EXITED'
                    self.core.navigation.cancel('MAPPING_PROCESS_EXITED')
                    self._clear_map()
                command = self._http('exchange', self._report())
                if command:
                    self.accept(command)
            except Exception as error:
                # No credentials, URLs, response bodies or maps in logs.
                import logging
                logging.getLogger(__name__).warning('Web mapping link unavailable (%s)', type(error).__name__)
                self.sent_grid = None  # Backend may have restarted; resend after recovery.
            self.stop.wait(1)

    def _grid_loop(self):
        while not self.stop.wait(1):
            try:
                grid, map_id = self._snapshot()
                if grid is not None and map_id != self.sent_grid and self.mode in ('MAPPING', 'NAVIGATION'):
                    display = map_display(grid, map_id, self.session)
                    if not self.stop.is_set() and self._snapshot()[1] == map_id:
                        self._http('grid', display, 'PUT')
                        self.sent_grid = map_id
            except Exception:
                # Status and command polling continues; a newer map replaces this one.
                self.sent_grid = None

    def accept(self, command):
        if (self.stop.is_set() or self.busy or command.get('session') != self.session
                or not isinstance(command.get('id'), str) or command['id'] in self.seen
                or not 0 <= command.get('expiresAt', 0)-time.time()*1000 <= 15000):
            return
        self.seen.add(command['id'])
        if len(self.seen)>1000:
            # Fail closed rather than forgetting command IDs that might be replayed.
            self.mode, self.message = 'ERROR', 'RESTART_MAPPING_SESSION'
            return
        self.busy, self.command_id, self.result, self.message = True, command['id'], 'RUNNING', None
        threading.Thread(target=self._execute, args=(command['request'],), daemon=True).start()

    def _execute(self, request):
        try:
            with self.lifecycle:
                with self.core._command_lock:
                    if self.core._shutting_down or self.stop.is_set():
                        raise ValueError('SHUTTING_DOWN')
                    self.core.motion.halt()
                    self.core.navigation.cancel('MAP_OPERATION')
                    self.core._send(0.0, 0.0)
                # Confirm measured standstill before changing localization or saving.
                deadline = time.monotonic()+5
                while True:
                    t = self.core.telemetry.report()
                    values = (t['linear_velocity'], t['angular_velocity'])
                    if self.core.telemetry.chassis_connected() and all(v is not None and math.isfinite(v) and abs(v)<0.03 for v in values):
                        break
                    if self.stop.wait(0.1) or time.monotonic()>deadline:
                        raise ValueError('ROBOT_MUST_BE_STOPPED')
                action = request['action']
                if action == 'START':
                    self._stop_pipeline()
                    self._clear_map()
                    self._ensure_exclusive()
                    self.map_source = '/web_slam_gmapping'
                    self._launch('cloud_mapping.launch')
                    self.mode = 'MAPPING'
                elif action == 'SAVE':
                    grid, _ = self._snapshot()
                    if self.mode != 'MAPPING' or grid is None:
                        raise ValueError('NO_MAPPING_GRID')
                    save_grid(self.root, request['name'], grid)
                elif action == 'LOAD':
                    target = saved_map(self.root, request['name'])
                    if not target.is_file():
                        raise ValueError('MAP_NOT_FOUND')
                    self._stop_pipeline()
                    self._clear_map()
                    self._ensure_exclusive()
                    self.map_source = '/map_server'
                    self._launch('cloud_navigation.launch', 'map_yaml:='+str(target))
                    deadline = time.monotonic()+15
                    while self._snapshot()[0] is None:
                        if self.stop.wait(0.1) or time.monotonic()>deadline or self.process.poll() is not None:
                            raise ValueError('MAP_LOAD_TIMEOUT')
                    self.mode = 'NAVIGATION'
                elif action == 'STOP':
                    self._stop_pipeline()
                    self._clear_map()
                    self.mode = 'IDLE'
                elif action == 'INITIAL_POSE':
                    self._initial_pose(request)
                else:
                    raise ValueError('UNKNOWN_MAP_ACTION')
                self.result, self.message = 'SUCCEEDED', action+'_APPLIED'
        except Exception as error:
            self.result = 'FAILED'
            self.message = str(error)[:160] if isinstance(error, ValueError) else type(error).__name__
        finally:
            self.busy = False

    def _initial_pose(self, request):
        grid, map_id = self._snapshot()
        if self.mode != 'NAVIGATION' or grid is None or request.get('mapId') != map_id:
            raise ValueError('MAP_CHANGED')
        p = request.get('pose') or {}
        if not all(isinstance(p.get(k), (float, int)) and math.isfinite(p[k]) for k in ('x', 'y', 'yaw')) or abs(p['yaw'])>math.pi:
            raise ValueError('INVALID_INITIAL_POSE')
        if not self.adapter.goal_allowed(p):
            raise ValueError('POSE_NOT_IN_FREE_MAP_SPACE')
        if self.initial.get_num_connections()<2:
            raise ValueError('INITIAL_POSE_SUBSCRIBER_UNAVAILABLE')
        msg = self.initial_type()
        msg.header.frame_id, msg.header.stamp = 'map', self.adapter.rospy.Time.now()
        msg.pose.pose.position.x, msg.pose.pose.position.y = p['x'], p['y']
        msg.pose.pose.orientation.z, msg.pose.pose.orientation.w = math.sin(p['yaw']/2), math.cos(p['yaw']/2)
        msg.pose.covariance[0] = msg.pose.covariance[7] = 0.25
        msg.pose.covariance[35] = (math.pi/12)**2
        self.initial.publish(msg)

    def _ensure_exclusive(self):
        code, _, system = self.adapter.rospy.get_master().getSystemState()
        if code != 1 or dict(system[0]).get('/map') or dict(system[0]).get('/move_base/status'):
            raise ValueError('EXTERNAL_MAP_OR_NAVIGATION_NODE')

    def _clear_map(self):
        with self.adapter.map_lock:
            self.map_source = None
            self.adapter.map_generation += 1
            self.adapter.map = self.adapter.map_id = self.adapter.confirmed_map = None
            self.adapter.map_snapshot = (None, None)
        self.adapter.pose = None
        self.adapter.health_cache, self.adapter.health_at = {}, 0
        self.scan, self.path = (None, 0), (None, 0)
        self.sent_grid = None

    def _launch(self, launch, *args):
        if self.stop.is_set():
            raise ValueError('SHUTTING_DOWN')
        env = dict(os.environ)
        for key in ('PYTHONHOME', 'PATROL_MAPPING_DEVICE_KEY', 'PATROL_CAMERA_DEVICE_KEY', 'SCOUT_RTSP_PASSWORD'):
            env.pop(key, None)
        self.process = subprocess.Popen([self.adapter.config['pose_python'], '/opt/ros/melodic/bin/roslaunch',
                                         str(HERE/'launch'/launch)]+list(args), env=env, start_new_session=True)
        if self.stop.wait(1) or self.process.poll() is not None:
            raise ValueError('ROS_LAUNCH_FAILED')

    def _stop_pipeline(self):
        process, self.process = self.process, None
        self.mode = 'IDLE'
        if process is not None:
            try:
                os.killpg(process.pid, signal.SIGINT)
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
            except ProcessLookupError:
                pass

    def close(self):
        self.stop.set()
        with self.lifecycle:
            self._stop_pipeline()
