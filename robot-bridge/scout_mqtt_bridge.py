#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MQTT <-> ROS bridge for the AgileX SCOUT MINI (exp3).

Runs on the robot's onboard computer next to the scout_base driver (ROS1 or ROS2):

* subscribes robot/{id}/control and turns FORWARD/BACKWARD/LEFT/RIGHT/STOP into
  geometry_msgs/Twist on /cmd_vel, with a watchdog that stops the robot once control
  messages stop arriving (0.6 s by default);
* publishes robot/{id}/heartbeat (1 Hz; its MQTT last will says offline),
  robot/{id}/status (2 Hz) and one receipt per command on robot/{id}/control/ack.

Examples:
  python3 scout_mqtt_bridge.py                          # ROS version detected, config.yaml
  python3 scout_mqtt_bridge.py --ros 1 --host 192.168.1.10
  python3 scout_mqtt_bridge.py --ros sim                # no ROS: simulated chassis for desk tests
"""
import argparse
import logging
import math
import os
import signal
import sys
import threading
import time

from scout_bridge_core import (
    LOG,
    BridgeCore,
    ConfigError,
    MqttLink,
    TelemetryCache,
    load_config,
    yaw_from_quaternion,
)

HERE = os.path.dirname(os.path.abspath(__file__))


def _scout_status_type():
    """scout_msgs/ScoutStatus from scout_ros or scout_ros2, if that package is installed."""
    try:
        from scout_msgs.msg import ScoutStatus
    except ImportError:
        return None
    return ScoutStatus


def _store_odometry(telemetry, msg):
    p = msg.pose.pose.position
    q = msg.pose.pose.orientation
    t = msg.twist.twist
    telemetry.update_odometry(p.x, p.y, yaw_from_quaternion(q.x, q.y, q.z, q.w), t.linear.x, t.angular.z)


def _store_status(telemetry, msg):
    # Field names shared by the ROS1 and ROS2 ScoutStatus messages.
    telemetry.update_chassis(msg.linear_velocity, msg.angular_velocity, msg.battery_voltage)


class Ros1Adapter(object):
    """rospy (ROS1 Noetic with Python 3)."""

    name = "ros1"

    def __init__(self, ros_config, telemetry):
        import rospy
        from geometry_msgs.msg import Twist
        from nav_msgs.msg import Odometry

        self._rospy = rospy
        self._twist = Twist
        self._telemetry = telemetry
        # Our own SIGINT/SIGTERM handling stops the robot before rospy shuts down.
        rospy.init_node(ros_config["node_name"], disable_signals=True)
        self._publisher = rospy.Publisher(ros_config["cmd_vel_topic"], Twist, queue_size=1)
        rospy.Subscriber(ros_config["odom_topic"], Odometry, self._on_odometry, queue_size=1)
        status_type = _scout_status_type()
        if status_type is None:
            telemetry.chassis_supported = False
            LOG.warning("scout_msgs not found: battery voltage and chassis link will be reported as null")
        else:
            rospy.Subscriber(ros_config["status_topic"], status_type, self._on_status, queue_size=1)

    def publish_velocity(self, linear, angular):
        msg = self._twist()
        msg.linear.x = linear
        msg.angular.z = angular
        self._publisher.publish(msg)

    def _on_odometry(self, msg):
        _store_odometry(self._telemetry, msg)

    def _on_status(self, msg):
        _store_status(self._telemetry, msg)

    def ok(self):
        return not self._rospy.is_shutdown()

    def shutdown(self):
        self._rospy.signal_shutdown("scout_mqtt_bridge exiting")


class Ros2Adapter(object):
    """rclpy (ROS2 Foxy/Humble); callbacks are spun on a background thread."""

    name = "ros2"

    def __init__(self, ros_config, telemetry):
        import rclpy
        from geometry_msgs.msg import Twist
        from nav_msgs.msg import Odometry
        from rclpy.executors import SingleThreadedExecutor

        self._rclpy = rclpy
        self._twist = Twist
        self._telemetry = telemetry
        rclpy.init(args=None)
        self._node = rclpy.create_node(ros_config["node_name"])
        self._publisher = self._node.create_publisher(Twist, ros_config["cmd_vel_topic"], 10)
        self._node.create_subscription(Odometry, ros_config["odom_topic"], self._on_odometry, 10)
        status_type = _scout_status_type()
        if status_type is None:
            telemetry.chassis_supported = False
            LOG.warning("scout_msgs not found: battery voltage and chassis link will be reported as null")
        else:
            self._node.create_subscription(status_type, ros_config["status_topic"], self._on_status, 10)
        self._executor = SingleThreadedExecutor()
        self._executor.add_node(self._node)
        self._spinner = threading.Thread(target=self._spin, name="ros2-spin")
        self._spinner.daemon = True
        self._spinner.start()

    def _spin(self):
        try:
            self._executor.spin()
        except Exception:  # raised by rclpy while shutting down
            LOG.debug("ROS2 executor stopped")

    def publish_velocity(self, linear, angular):
        msg = self._twist()
        msg.linear.x = linear
        msg.angular.z = angular
        self._publisher.publish(msg)

    def _on_odometry(self, msg):
        _store_odometry(self._telemetry, msg)

    def _on_status(self, msg):
        _store_status(self._telemetry, msg)

    def ok(self):
        return self._rclpy.ok()

    def shutdown(self):
        self._executor.shutdown()
        self._node.destroy_node()
        if self._rclpy.ok():
            self._rclpy.shutdown()


class SimAdapter(object):
    """Desk test without ROS or hardware: integrates the published velocity into odometry.

    Like a base without its own command timeout, it keeps the last velocity until a new one
    arrives, so it is the bridge's watchdog that stops it. Battery voltage is only reported when
    configured (sim.battery_voltage); every message says source=sim.
    """

    name = "sim"

    def __init__(self, telemetry, battery_voltage=None, clock=time.monotonic):
        self._telemetry = telemetry
        self._voltage = battery_voltage
        self._clock = clock
        self._lock = threading.Lock()
        self._x = self._y = self._yaw = 0.0
        self._linear = self._angular = 0.0
        self._last = clock()
        self.spin_once()

    def publish_velocity(self, linear, angular):
        with self._lock:
            self._integrate()
            self._linear, self._angular = linear, angular

    def spin_once(self):
        with self._lock:
            self._integrate()
            pose = (self._x, self._y, self._yaw, self._linear, self._angular)
        self._telemetry.update_odometry(*pose)
        self._telemetry.update_chassis(pose[3], pose[4], self._voltage)

    def _integrate(self):
        now = self._clock()
        dt, self._last = now - self._last, now
        self._x += self._linear * math.cos(self._yaw) * dt
        self._y += self._linear * math.sin(self._yaw) * dt
        yaw = self._yaw + self._angular * dt
        self._yaw = math.atan2(math.sin(yaw), math.cos(yaw))

    def ok(self):
        return True

    def shutdown(self):
        pass


def detect_ros():
    """'1' or '2' from ROS_VERSION or the importable client library."""
    version = os.environ.get("ROS_VERSION")
    if version in ("1", "2"):
        return version
    for module, found in (("rclpy", "2"), ("rospy", "1")):
        try:
            __import__(module)
            return found
        except ImportError:
            pass
    raise ConfigError(
        "no ROS environment found: source /opt/ros/<distro>/setup.bash and the scout workspace, "
        "or use --ros sim for a desk test"
    )


def make_adapter(kind, config, telemetry):
    if kind == "sim":
        return SimAdapter(telemetry, config["sim"]["battery_voltage"])
    if kind == "1":
        return Ros1Adapter(config["ros"], telemetry)
    return Ros2Adapter(config["ros"], telemetry)


def read_config_file(path):
    try:
        import yaml
    except ImportError:
        raise ConfigError("PyYAML is required to read %s (pip3 install PyYAML)" % path)
    with open(path, "r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    return data or {}


def parse_args(argv):
    parser = argparse.ArgumentParser(description="MQTT <-> ROS bridge for the AgileX SCOUT MINI")
    parser.add_argument("--config", help="YAML file (default: config.yaml next to this script)")
    parser.add_argument("--ros", choices=("auto", "1", "2", "sim"), default="auto", help="ROS version; sim = no ROS")
    parser.add_argument("--robot-id", help="overrides robot_id")
    parser.add_argument("--host", help="overrides mqtt.host")
    parser.add_argument("--port", type=int, help="overrides mqtt.port")
    parser.add_argument("--log-level", default="INFO", choices=("DEBUG", "INFO", "WARNING", "ERROR"))
    return parser.parse_args(argv)


def build_config(args):
    path = args.config or os.path.join(HERE, "config.yaml")
    if args.config and not os.path.isfile(path):
        raise ConfigError("config file not found: %s" % path)
    overrides = read_config_file(path) if os.path.isfile(path) else {}
    if args.robot_id:
        overrides["robot_id"] = args.robot_id
    for key, value in (("host", args.host), ("port", args.port)):
        if value is not None:
            overrides.setdefault("mqtt", {})
            overrides["mqtt"] = dict(overrides["mqtt"] or {}, **{key: value})
    return load_config(overrides)


def main(argv=None):
    args = parse_args(argv)
    logging.basicConfig(level=args.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        config = build_config(args)
        kind = detect_ros() if args.ros == "auto" else args.ros
    except ConfigError as error:
        LOG.error("%s", error)
        return 2

    telemetry = TelemetryCache(config["telemetry"]["chassis_timeout"])
    adapter = make_adapter(kind, config, telemetry)
    core = BridgeCore(config, adapter, telemetry, adapter.name)
    link = MqttLink(config["mqtt"], config["robot_id"], core.on_connected, core.on_disconnected, core.on_control)
    core.bind(link)

    stop = threading.Event()
    for name in ("SIGINT", "SIGTERM", "SIGBREAK"):  # SIGBREAK: Ctrl+Break on Windows desk tests
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), lambda *_: stop.set())

    LOG.info(
        "bridge %s: robot %s, broker %s:%s, %.2f m/s / %.2f rad/s, watchdog %.2f s",
        adapter.name,
        config["robot_id"],
        config["mqtt"]["host"],
        config["mqtt"]["port"],
        config["motion"]["linear_speed"],
        config["motion"]["angular_speed"],
        config["safety"]["watchdog_timeout"],
    )
    link.start()
    period = 1.0 / config["safety"]["publish_rate"]
    spin = getattr(adapter, "spin_once", None)
    try:
        deadline = time.monotonic()
        while not stop.is_set() and adapter.ok():
            if spin is not None:
                spin()
            core.step()
            deadline += period
            delay = deadline - time.monotonic()
            if delay > 0:
                stop.wait(delay)
            else:  # fell behind: do not burst to catch up
                deadline = time.monotonic()
    finally:
        LOG.info("stopping: zero velocity and offline heartbeat")
        core.shutdown()
        link.stop()
        adapter.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
