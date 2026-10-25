# -*- coding: utf-8 -*-
"""ROS-independent core of the SCOUT MINI MQTT bridge (exp3).

Everything that can be tested without ROS lives here: configuration, the command-to-velocity
mapping, the safety watchdog, duplicate filtering, telemetry bookkeeping, the MQTT message
formats and a small paho-mqtt wrapper. The ROS side (rospy, rclpy or the desk simulator) plugs
in through an adapter with ``publish_velocity(linear, angular)``; see scout_mqtt_bridge.py.

Message formats: exp3/docs/MQTT-PROTOCOL.md. Requires Python 3.6+.
"""
import copy
import json
import logging
import math
import re
import threading
import time
import uuid

LOG = logging.getLogger("scout_bridge")

COMMANDS = ("FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP")
#: Caps a config file cannot raise: the chassis is much faster than a lab or corridor allows.
MAX_LINEAR_SPEED = 1.0  # m/s
MAX_ANGULAR_SPEED = 1.5  # rad/s
#: Requirement PER-006: stop within 1 s once control messages stop arriving.
MAX_WATCHDOG_TIMEOUT = 1.0
MAX_PAYLOAD_BYTES = 4096
_ROBOT_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

DEFAULT_CONFIG = {
    "robot_id": "SCOUT-001",
    "mqtt": {
        "host": "127.0.0.1",
        "port": 1883,
        "username": None,
        "password": None,
        "client_id": None,
        "keepalive": 5,
        "reconnect_max": 5,
        "tls": False,
        "ca_certs": None,
    },
    "motion": {"linear_speed": 0.3, "angular_speed": 0.5},
    "safety": {"watchdog_timeout": 0.6, "publish_rate": 20.0, "stop_frames": 10},
    "telemetry": {"heartbeat_period": 1.0, "status_period": 0.5, "chassis_timeout": 1.0},
    "ros": {
        "node_name": "scout_mqtt_bridge",
        "cmd_vel_topic": "/cmd_vel",
        "odom_topic": "/odom",
        "status_topic": "/scout_status",
    },
    "sim": {"battery_voltage": None},
    "navigation": {"enabled": False, "mode": "map", "direct": False, "action": "/move_base", "frame": "map", "base_frame": "base_link",
                   "pose_topic": "/patrol/navigation_pose", "pose_python": "/usr/bin/python2.7",
                   "map_topic": "/map", "scan_topic": "/scan", "costmap_topic": "/move_base/local_costmap/costmap",
                   "velocity_topic": "/navigation/cmd_vel", "max_linear": 0.2, "max_angular": 0.4, "goal_timeout": 180},
}


class ConfigError(ValueError):
    """Invalid or unsafe configuration."""


def load_config(overrides=None):
    """Defaults merged with ``overrides`` (e.g. parsed config.yaml), then validated."""
    config = copy.deepcopy(DEFAULT_CONFIG)
    _merge(config, overrides or {}, "")
    _validate(config)
    return config


def _merge(target, source, path):
    if not isinstance(source, dict):
        raise ConfigError("%s must be a mapping" % (path or "config"))
    for key, value in source.items():
        name = path + key
        if key not in target:
            raise ConfigError("unknown option: %s" % name)
        if isinstance(target[key], dict):
            _merge(target[key], value if value is not None else {}, name + ".")
        else:
            target[key] = value


def _number(config, section, key, low, high, integer=False):
    value = config[section][key]
    kind = int if integer else (int, float)
    if isinstance(value, bool) or not isinstance(value, kind) or not low <= value <= high:
        raise ConfigError("%s.%s must be between %s and %s" % (section, key, low, high))
    config[section][key] = value if integer else float(value)


def _validate(config):
    if not isinstance(config["robot_id"], str) or not _ROBOT_ID.match(config["robot_id"]):
        raise ConfigError("robot_id must be 1-64 letters, digits, '_' or '-' (it is part of every topic)")
    mqtt = config["mqtt"]
    for key in ('username', 'password'):
        if mqtt[key] is not None and not isinstance(mqtt[key], str):
            raise ConfigError('mqtt.%s must be a quoted string' % key)
    nav = config['navigation']
    if not isinstance(nav['enabled'], bool):
        raise ConfigError('navigation.enabled must be boolean')
    if nav['mode'] not in ('map', 'relative'):
        raise ConfigError('navigation.mode must be map or relative')
    if not isinstance(nav['direct'], bool) or (nav['direct'] and nav['mode'] != 'relative'):
        raise ConfigError('navigation.direct requires relative mode and a boolean value')
    for key in ('action', 'frame', 'base_frame', 'map_topic', 'scan_topic', 'costmap_topic', 'velocity_topic', 'pose_topic', 'pose_python'):
        if not isinstance(nav[key], str) or not nav[key]:
            raise ConfigError('navigation.%s must be nonempty' % key)
    if nav['velocity_topic'] == config['ros']['cmd_vel_topic']:
        raise ConfigError('Navigation velocity must be isolated from chassis cmd_vel')
    _number(config, 'navigation', 'max_linear', 0.01, 0.3)
    _number(config, 'navigation', 'max_angular', 0.01, 0.6)
    _number(config, 'navigation', 'goal_timeout', 10, 600)
    if nav['mode'] == 'relative':
        nav['frame'] = 'odom'
        nav['map_topic'] = '/move_base/global_costmap/costmap'
        nav['max_linear'] = min(nav['max_linear'], 0.15)
        nav['max_angular'] = min(nav['max_angular'], 0.3)
        nav['goal_timeout'] = min(nav['goal_timeout'], 60)
    if not isinstance(mqtt["host"], str) or not mqtt["host"].strip():
        raise ConfigError("mqtt.host must be the broker address")
    _number(config, "mqtt", "port", 1, 65535, integer=True)
    _number(config, "mqtt", "keepalive", 1, 300, integer=True)
    _number(config, "mqtt", "reconnect_max", 1, 120, integer=True)
    _number(config, "motion", "linear_speed", 0.01, MAX_LINEAR_SPEED)
    _number(config, "motion", "angular_speed", 0.01, MAX_ANGULAR_SPEED)
    _number(config, "safety", "watchdog_timeout", 0.1, MAX_WATCHDOG_TIMEOUT)
    _number(config, "safety", "publish_rate", 5, 100)
    _number(config, "safety", "stop_frames", 1, 100, integer=True)
    _number(config, "telemetry", "heartbeat_period", 0.2, 5)
    _number(config, "telemetry", "status_period", 0.1, 5)
    _number(config, "telemetry", "chassis_timeout", 0.2, 10)
    if config["safety"]["watchdog_timeout"] < 2.0 / config["safety"]["publish_rate"]:
        raise ConfigError("safety.watchdog_timeout must cover at least two publish cycles")
    for key in ("node_name", "cmd_vel_topic", "odom_topic", "status_topic"):
        if not isinstance(config["ros"][key], str) or not config["ros"][key]:
            raise ConfigError("ros.%s must be a non-empty string" % key)
    voltage = config["sim"]["battery_voltage"]
    if voltage is not None and (isinstance(voltage, bool) or not isinstance(voltage, (int, float))):
        raise ConfigError("sim.battery_voltage must be a number or null")


def topics(robot_id):
    """MQTT topics of requirement table A-4."""
    base = "robot/%s/" % robot_id
    return {
        "control": base + "control",
        "status": base + "status",
        "heartbeat": base + "heartbeat",
        "ack": base + "control/ack",
    }


def yaw_from_quaternion(x, y, z, w):
    """Heading (rad, [-pi, pi]) of an orientation quaternion."""
    return math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))


def _finite(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return round(number, 4) if math.isfinite(number) else None


def _pick(*values):
    for value in values:
        if value is not None:
            return value
    return None


class MotionController(object):
    """Maps commands to velocities and enforces the watchdog.

    Motion only continues while control messages keep arriving: after ``watchdog_timeout``
    seconds without one, the controller switches to STOP by itself. After any stop it publishes
    ``stop_frames`` zero velocities and then stays silent, so an idle bridge does not hold
    /cmd_vel against the remote control or other nodes.
    """

    def __init__(self, linear_speed, angular_speed, watchdog_timeout, stop_frames, clock=time.monotonic):
        self._velocities = {
            "FORWARD": (linear_speed, 0.0),
            "BACKWARD": (-linear_speed, 0.0),
            "LEFT": (0.0, angular_speed),  # REP-103: positive yaw rate turns left (in place)
            "RIGHT": (0.0, -angular_speed),
        }
        self._timeout = watchdog_timeout
        self._stop_frames = stop_frames
        self._clock = clock
        self._lock = threading.Lock()
        self._state = "STOPPED"
        self._last_command_at = None
        self._zeros_left = 0
        self.watchdog_trips = 0

    @property
    def state(self):
        with self._lock:
            return self._state

    def apply(self, command):
        """Apply a validated command and return the velocity to publish right away."""
        with self._lock:
            if command == "STOP":
                self._halt()
                return (0.0, 0.0)
            self._state = command
            self._last_command_at = self._clock()
            self._zeros_left = 0
            return self._velocities[command]

    def halt(self):
        """Stop at once (MQTT link lost, shutting down)."""
        with self._lock:
            self._halt()

    def tick(self):
        """Velocity to publish in this control cycle, or None to stay quiet."""
        with self._lock:
            if self._state != "STOPPED":
                if self._clock() - self._last_command_at <= self._timeout:
                    return self._velocities[self._state]
                self.watchdog_trips += 1
                LOG.warning("watchdog: no control message for %.2f s, stopping", self._timeout)
                self._halt()
            if self._zeros_left > 0:
                self._zeros_left -= 1
                return (0.0, 0.0)
            return None

    def _halt(self):
        self._state = "STOPPED"
        self._last_command_at = None
        self._zeros_left = self._stop_frames


class SequenceFilter(object):
    """Drops redelivered (QoS 1) or reordered control messages within one backend session.

    Messages without session/seq (e.g. typed by hand with mosquitto_pub) are always accepted.
    A different session means the backend restarted, so numbering starts over.
    """

    def __init__(self):
        self._session = None
        self._last = None

    def accept(self, session, seq):
        if session is None or seq is None:
            return True
        if session != self._session:
            self._session, self._last = session, seq
            return True
        if seq <= self._last:
            return False
        self._last = seq
        return True


class TelemetryCache(object):
    """Latest chassis status and odometry; values older than ``max_age`` are reported as None."""

    def __init__(self, max_age, clock=time.monotonic):
        self._max_age = max_age
        self._clock = clock
        self._lock = threading.Lock()
        self._chassis = None  # (time, linear, angular, battery_voltage)
        self._odometry = None  # (time, x, y, yaw, linear, angular)
        #: False when the chassis status message type is not installed; chassis link is unknown.
        self.chassis_supported = True

    def update_chassis(self, linear, angular, battery_voltage):
        with self._lock:
            self._chassis = (self._clock(), _finite(linear), _finite(angular), _finite(battery_voltage))

    def update_odometry(self, x, y, yaw, linear=None, angular=None):
        with self._lock:
            self._odometry = (
                self._clock(),
                _finite(x),
                _finite(y),
                _finite(yaw),
                _finite(linear),
                _finite(angular),
            )

    def chassis_connected(self):
        """Whether chassis status arrives; None when it cannot be observed at all."""
        if not self.chassis_supported:
            return None
        with self._lock:
            return self._fresh(self._chassis)

    def report(self):
        """Telemetry fields of the status message. Nothing is estimated or invented."""
        with self._lock:
            chassis = self._chassis if self._fresh(self._chassis) else None
            odometry = self._odometry if self._fresh(self._odometry) else None
        return {
            "battery_pct": None,  # scout_base publishes no state of charge
            "battery_voltage": chassis[3] if chassis else None,
            "linear_velocity": _pick(chassis[1] if chassis else None, odometry[4] if odometry else None),
            "angular_velocity": _pick(chassis[2] if chassis else None, odometry[5] if odometry else None),
            "position": {
                "x": odometry[1] if odometry else None,
                "y": odometry[2] if odometry else None,
                "yaw": odometry[3] if odometry else None,
            },
        }

    def _fresh(self, sample):
        return sample is not None and self._clock() - sample[0] <= self._max_age


class BridgeCore(object):
    """MQTT protocol and control loop, independent of how velocities reach the chassis.

    ``ros`` needs ``publish_velocity(linear, angular)``; the MQTT link (bound later with
    ``bind``) needs ``connected``, ``subscribe(topic, qos)`` and ``publish(topic, payload, qos)``.
    """

    def __init__(self, config, ros, telemetry, ros_name, clock=time.monotonic, wall=time.time):
        self.robot_id = config["robot_id"]
        self.topics = topics(self.robot_id)
        self.motion = MotionController(
            config["motion"]["linear_speed"],
            config["motion"]["angular_speed"],
            config["safety"]["watchdog_timeout"],
            config["safety"]["stop_frames"],
            clock,
        )
        self.sequence = SequenceFilter()
        self.telemetry = telemetry
        self._ros = ros
        self._ros_name = ros_name
        self._clock = clock
        self._wall = wall
        self._heartbeat_period = config["telemetry"]["heartbeat_period"]
        self._status_period = config["telemetry"]["status_period"]
        self._next_heartbeat = None
        self._next_status = None
        self._link = None
        self._link_down_since = None
        self._next_link_warning = None
        self.navigation = None
        self._command_lock = threading.RLock()
        self._shutting_down = False

    def bind(self, link):
        self._link = link

    # --- MQTT side (paho network thread) -------------------------------------------------

    def on_connected(self):
        if self._shutting_down:
            return
        LOG.info("MQTT connected, listening on %s", self.topics["control"])
        self._link.subscribe(self.topics["control"], 1)
        self._link.subscribe('robot/%s/navigation/command' % self.robot_id, 1)
        self._publish_heartbeat()
        self._next_status = None  # full state on the next cycle, not up to a period later

    def on_disconnected(self):
        if self.navigation:
            self.navigation.cancel('MQTT_DISCONNECTED')
        if self.motion.state != "STOPPED":
            LOG.warning("MQTT connection lost while %s: stopping", self.motion.state)
        self.motion.halt()
        self._send(0.0, 0.0)

    def on_control(self, payload, retained=False):
        with self._command_lock:
            if not self._shutting_down:
                self._control(payload, retained)

    def _control(self, payload, retained=False):
        if retained:
            LOG.warning("ignoring a retained control message: commands must be live")
            return
        message = _parse(payload)
        if message is None:
            LOG.warning("ignoring a malformed control message")
            return
        if message.get("robot_id") not in (None, self.robot_id):
            return
        seq, session = message.get("seq"), message.get("session")
        if not _valid_seq(seq) or not (session is None or (isinstance(session, str) and len(session) <= 64)):
            LOG.warning("ignoring a control message with an invalid seq/session")
            return
        command = message.get("command")
        if command not in COMMANDS:
            self._ack(seq, session, None, "REJECTED", "INVALID_COMMAND")
            return
        if not self.sequence.accept(session, seq):
            LOG.debug("dropping duplicate or out-of-order control seq=%s", seq)
            return
        if self.navigation:
            self.navigation.cancel('MANUAL_OVERRIDE')
        self._send(*self.motion.apply(command))
        self._ack(seq, session, command, "APPLIED", None)

    # --- control loop (main thread) ------------------------------------------------------

    def step(self):
        """One control cycle: watchdog, /cmd_vel, heartbeat and status when due."""
        navigating = self.navigation.tick() if self.navigation else False
        velocity = None if navigating else self.motion.tick()
        if velocity is not None:
            self._send(*velocity)
        now = self._clock()
        self._watch_link(now)
        if self._next_heartbeat is None or now >= self._next_heartbeat:
            self._publish_heartbeat()
        if self._next_status is None or now >= self._next_status:
            self._publish(self.topics["status"], self.status_message(), 0)
            self._next_status = now + self._status_period
            if self.navigation:
                report = dict(self.navigation.report(), robot_id=self.robot_id)
                self._publish('robot/%s/navigation/status' % self.robot_id, report, 0)

    def on_navigation(self, payload, retained=False):
        with self._command_lock:
            if not self._shutting_down:
                self._navigation(payload, retained)

    def _navigation(self, payload, retained=False):
        if retained or not self.navigation:
            return
        message = _parse(payload)
        if not message or message.get('robot_id') != self.robot_id:
            return
        seq, session = message.get('seq'), message.get('session')
        if not isinstance(seq, int) or isinstance(seq, bool) or seq < 1 or not isinstance(session, str) or len(session) > 64:
            return
        if not self.sequence.accept(session, seq):
            return
        self.motion.halt()
        self.navigation.command(message)

    def shutdown(self):
        """Stop the robot and tell the backend this is a planned exit, not a crash."""
        # Close command admission and stop manual motion BEFORE waiting for a helper process.
        with self._command_lock:
            self._shutting_down = True
            self.motion.halt()
            for _ in range(3):
                self._send(0.0, 0.0)
        if self.navigation:
            self.navigation.shutdown()
        payload = {"robot_id": self.robot_id, "online": False, "reason": "SHUTDOWN", "timestamp": self._now_ms()}
        if self._link is not None and self._link.connected:
            self._link.publish(self.topics["heartbeat"], _dumps(payload), 1, wait=1.0)

    def heartbeat_message(self):
        return {
            "robot_id": self.robot_id,
            "online": True,
            "chassis_connected": self.telemetry.chassis_connected(),
            "ros": self._ros_name,
            "watchdog_trips": self.motion.watchdog_trips,
            "timestamp": self._now_ms(),
        }

    def status_message(self):
        message = {"robot_id": self.robot_id, "motion_state": self.motion.state}
        message.update(self.telemetry.report())
        message["chassis_connected"] = self.telemetry.chassis_connected()
        message["source"] = self._ros_name
        message["timestamp"] = self._now_ms()
        return message

    # --- helpers -------------------------------------------------------------------------

    def _watch_link(self, now):
        """paho retries silently; say so when the broker stays unreachable."""
        if self._link is None or self._link.connected:
            self._link_down_since = None
            return
        if self._link_down_since is None:
            self._link_down_since, self._next_link_warning = now, now + 5.0
        elif now >= self._next_link_warning:
            LOG.warning(
                "no MQTT broker connection for %.0f s: check mqtt.host/port, the network and the broker",
                now - self._link_down_since,
            )
            self._next_link_warning = now + 10.0

    def _publish_heartbeat(self):
        self._publish(self.topics["heartbeat"], self.heartbeat_message(), 0)
        self._next_heartbeat = self._clock() + self._heartbeat_period

    def _ack(self, seq, session, command, result, reason):
        self._publish(
            self.topics["ack"],
            {
                "robot_id": self.robot_id,
                "seq": seq,
                "session": session,
                "command": command,
                "result": result,
                "reason": reason,
                "timestamp": self._now_ms(),
            },
            0,
        )

    def _send(self, linear, angular):
        try:
            self._ros.publish_velocity(linear, angular)
        except Exception:  # keep the control loop alive; the watchdog still applies
            LOG.exception("publishing the velocity command failed")

    def _publish(self, topic, message, qos):
        if self._link is not None and self._link.connected:
            self._link.publish(topic, _dumps(message), qos)

    def _now_ms(self):
        return int(self._wall() * 1000)


def _dumps(message):
    return json.dumps(message, separators=(",", ":"))


def _parse(payload):
    if payload is None or len(payload) > MAX_PAYLOAD_BYTES:
        return None
    try:
        if isinstance(payload, bytes):
            payload = payload.decode("utf-8")
        message = json.loads(payload)
    except (UnicodeDecodeError, ValueError):
        return None
    return message if isinstance(message, dict) else None


def _valid_seq(seq):
    return seq is None or (isinstance(seq, int) and not isinstance(seq, bool) and seq >= 0)


def _failed(reason_code):
    """paho 2.x passes a ReasonCode object, paho 1.x a plain int (0 = success)."""
    is_failure = getattr(reason_code, "is_failure", None)
    if is_failure is not None:
        return bool(is_failure)
    return reason_code != 0


class MqttLink(object):
    """paho-mqtt client (1.x and 2.x) that reconnects by itself and leaves an offline heartbeat
    as its last will, so the backend learns about a crash or network loss right away."""

    def __init__(self, mqtt_config, robot_id, on_connected, on_disconnected, on_control, paho=None):
        self._robot_id = robot_id
        if paho is None:
            import paho.mqtt.client as paho  # imported lazily: the core tests do not need it
        self._config = mqtt_config
        self._topics = topics(robot_id)
        self._on_connected = on_connected
        self._on_disconnected = on_disconnected
        self._on_control = on_control
        self.connected = False
        client_id = mqtt_config["client_id"] or "scout-bridge-%s-%s" % (robot_id, uuid.uuid4().hex[:6])
        version = getattr(paho, "CallbackAPIVersion", None)
        if version is not None:  # paho-mqtt >= 2.0
            self._client = paho.Client(version.VERSION2, client_id=client_id, clean_session=True)
        else:
            self._client = paho.Client(client_id=client_id, clean_session=True)
        will = {"robot_id": robot_id, "online": False, "reason": "LWT"}
        self._client.will_set(self._topics["heartbeat"], _dumps(will), qos=1, retain=False)
        if mqtt_config["username"]:
            self._client.username_pw_set(mqtt_config["username"], mqtt_config["password"])
        if mqtt_config["tls"]:
            self._client.tls_set(ca_certs=mqtt_config["ca_certs"])
        self._client.reconnect_delay_set(min_delay=1, max_delay=mqtt_config["reconnect_max"])
        self._client.on_connect = self._handle_connect
        self._client.on_disconnect = self._handle_disconnect
        self._client.on_message = self._handle_message
        self.navigation_handler = None

    def start(self):
        """Connect in the background; paho keeps retrying until the broker is reachable."""
        self._client.connect_async(self._config["host"], self._config["port"], keepalive=self._config["keepalive"])
        self._client.loop_start()

    def stop(self):
        try:
            self._client.disconnect()
        finally:
            self._client.loop_stop()

    def subscribe(self, topic, qos):
        self._client.subscribe(topic, qos)

    def publish(self, topic, payload, qos=0, wait=0.0):
        """Never retained. With ``wait`` > 0, block up to that many seconds for delivery."""
        if not self.connected:
            return False
        info = self._client.publish(topic, payload, qos=qos, retain=False)
        deadline = time.monotonic() + wait
        while wait > 0 and not info.is_published() and time.monotonic() < deadline:
            time.sleep(0.02)
        return info.rc == 0

    def _handle_connect(self, client, userdata, flags, reason_code, properties=None):
        if _failed(reason_code):
            LOG.error("MQTT broker refused the connection: %s", reason_code)
            return
        self.connected = True
        self._on_connected()

    def _handle_disconnect(self, client, userdata, *args):
        self.connected = False
        self._on_disconnected()

    def _handle_message(self, client, userdata, message):
        if message.topic == self._topics["control"]:
            self._on_control(message.payload, bool(message.retain))
        elif message.topic == 'robot/%s/navigation/command' % self._robot_id and self.navigation_handler:
            self.navigation_handler(message.payload, bool(message.retain))
