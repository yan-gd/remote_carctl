# -*- coding: utf-8 -*-
"""ROS adapter wiring, checked against stand-in rospy / rclpy / message modules.

This proves topic names, message fields and callbacks are wired as the scout_ros(2) sources
require; it is not a substitute for a run on the real robot.
"""
import math
import os
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import scout_bridge_core as core  # noqa: E402
import scout_mqtt_bridge as bridge  # noqa: E402


class Vector3(object):
    def __init__(self, x=0.0, y=0.0, z=0.0):
        self.x, self.y, self.z = x, y, z


class Twist(object):
    def __init__(self):
        self.linear = Vector3()
        self.angular = Vector3()


class Odometry(object):
    pass


class ScoutStatus(object):
    pass


def odometry(x, y, yaw, linear, angular):
    msg = types.SimpleNamespace()
    msg.pose = types.SimpleNamespace(
        pose=types.SimpleNamespace(
            position=Vector3(x, y, 0.0),
            orientation=types.SimpleNamespace(x=0.0, y=0.0, z=math.sin(yaw / 2), w=math.cos(yaw / 2)),
        )
    )
    msg.twist = types.SimpleNamespace(twist=types.SimpleNamespace(linear=Vector3(linear), angular=Vector3(z=angular)))
    return msg


def scout_status(linear, angular, voltage):
    return types.SimpleNamespace(linear_velocity=linear, angular_velocity=angular, battery_voltage=voltage)


def message_modules(with_scout=True):
    modules = {}
    for package, names in (("geometry_msgs", {"Twist": Twist}), ("nav_msgs", {"Odometry": Odometry})):
        modules[package] = types.ModuleType(package)
        modules[package + ".msg"] = types.ModuleType(package + ".msg")
        for name, value in names.items():
            setattr(modules[package + ".msg"], name, value)
    if with_scout:
        modules["scout_msgs"] = types.ModuleType("scout_msgs")
        modules["scout_msgs.msg"] = types.ModuleType("scout_msgs.msg")
        modules["scout_msgs.msg"].ScoutStatus = ScoutStatus
    else:
        modules["scout_msgs"] = None  # makes "import scout_msgs" raise ImportError
        modules["scout_msgs.msg"] = None
    return modules


def fake_rospy():
    rospy = types.ModuleType("rospy")
    rospy.publishers, rospy.subscribers, rospy.state = [], [], {}

    class Publisher(object):
        def __init__(self, topic, msg_type, queue_size=None):
            self.topic, self.msg_type, self.queue_size, self.sent = topic, msg_type, queue_size, []
            rospy.publishers.append(self)

        def publish(self, msg):
            self.sent.append(msg)

    rospy.Publisher = Publisher
    rospy.Subscriber = lambda topic, msg_type, callback, queue_size=None: rospy.subscribers.append(
        (topic, msg_type, callback)
    )
    rospy.init_node = lambda name, disable_signals=False: rospy.state.update(node=name, signals=not disable_signals)
    rospy.is_shutdown = lambda: "shutdown" in rospy.state
    rospy.signal_shutdown = lambda reason: rospy.state.update(shutdown=reason)
    return rospy


def fake_rclpy():
    rclpy = types.ModuleType("rclpy")
    executors = types.ModuleType("rclpy.executors")
    rclpy.state = {"ok": False}
    rclpy.nodes = []

    class Publisher(object):
        def __init__(self, msg_type, topic, qos):
            self.msg_type, self.topic, self.qos, self.sent = msg_type, topic, qos, []

        def publish(self, msg):
            self.sent.append(msg)

    class Node(object):
        def __init__(self, name):
            self.name, self.publishers, self.subscriptions, self.destroyed = name, [], [], False
            rclpy.nodes.append(self)

        def create_publisher(self, msg_type, topic, qos):
            publisher = Publisher(msg_type, topic, qos)
            self.publishers.append(publisher)
            return publisher

        def create_subscription(self, msg_type, topic, callback, qos):
            self.subscriptions.append((msg_type, topic, callback, qos))

        def destroy_node(self):
            self.destroyed = True

    class SingleThreadedExecutor(object):
        def __init__(self):
            self.nodes, self.stopped = [], threading.Event()

        def add_node(self, node):
            self.nodes.append(node)

        def spin(self):
            self.stopped.wait(5)

        def shutdown(self):
            self.stopped.set()

    rclpy.init = lambda args=None: rclpy.state.update(ok=True)
    rclpy.create_node = Node
    rclpy.ok = lambda: rclpy.state["ok"]
    rclpy.shutdown = lambda: rclpy.state.update(ok=False)
    executors.SingleThreadedExecutor = SingleThreadedExecutor
    rclpy.executors = executors
    return {"rclpy": rclpy, "rclpy.executors": executors}


ROS_CONFIG = core.load_config()["ros"]


class Ros1AdapterTest(unittest.TestCase):
    def test_wiring_matches_scout_ros(self):
        rospy = fake_rospy()
        telemetry = core.TelemetryCache(1.0)
        with mock.patch.dict(sys.modules, dict(message_modules(), rospy=rospy)):
            adapter = bridge.Ros1Adapter(ROS_CONFIG, telemetry)
        self.assertEqual({"node": "scout_mqtt_bridge", "signals": False}, rospy.state)
        publisher = rospy.publishers[0]
        self.assertEqual(("/cmd_vel", Twist, 1), (publisher.topic, publisher.msg_type, publisher.queue_size))
        topics = {topic: (msg_type, callback) for topic, msg_type, callback in rospy.subscribers}
        self.assertEqual(Odometry, topics["/odom"][0])
        self.assertEqual(ScoutStatus, topics["/scout_status"][0])

        adapter.publish_velocity(0.3, -0.5)
        sent = publisher.sent[-1]
        self.assertEqual((0.3, 0.0, 0.0), (sent.linear.x, sent.linear.y, sent.linear.z))
        self.assertEqual((0.0, 0.0, -0.5), (sent.angular.x, sent.angular.y, sent.angular.z))

        topics["/odom"][1](odometry(1.5, -2.0, 0.75, 0.3, 0.1))
        topics["/scout_status"][1](scout_status(0.29, 0.1, 25.6))
        report = telemetry.report()
        self.assertEqual({"x": 1.5, "y": -2.0, "yaw": 0.75}, report["position"])
        self.assertEqual((0.29, 25.6), (report["linear_velocity"], report["battery_voltage"]))
        self.assertTrue(telemetry.chassis_connected())

        self.assertTrue(adapter.ok())
        adapter.shutdown()
        self.assertFalse(adapter.ok())

    def test_without_scout_msgs_the_chassis_is_unknown(self):
        rospy = fake_rospy()
        telemetry = core.TelemetryCache(1.0)
        with mock.patch.dict(sys.modules, dict(message_modules(with_scout=False), rospy=rospy)):
            bridge.Ros1Adapter(ROS_CONFIG, telemetry)
        self.assertEqual(["/odom"], [topic for topic, _, _ in rospy.subscribers])
        self.assertIsNone(telemetry.chassis_connected())


class Ros2AdapterTest(unittest.TestCase):
    def test_wiring_matches_scout_ros2(self):
        modules = fake_rclpy()
        telemetry = core.TelemetryCache(1.0)
        config = dict(ROS_CONFIG, cmd_vel_topic="/scout/cmd_vel")
        with mock.patch.dict(sys.modules, dict(message_modules(), **modules)):
            adapter = bridge.Ros2Adapter(config, telemetry)
        rclpy = modules["rclpy"]
        node = rclpy.nodes[0]
        self.assertEqual("scout_mqtt_bridge", node.name)
        self.assertEqual(("/scout/cmd_vel", Twist), (node.publishers[0].topic, node.publishers[0].msg_type))
        subscriptions = {topic: (msg_type, callback) for msg_type, topic, callback, _ in node.subscriptions}
        self.assertEqual(Odometry, subscriptions["/odom"][0])
        self.assertEqual(ScoutStatus, subscriptions["/scout_status"][0])

        adapter.publish_velocity(-0.3, 0.0)
        self.assertEqual(-0.3, node.publishers[0].sent[-1].linear.x)
        subscriptions["/scout_status"][1](scout_status(0.0, 0.0, 24.9))
        self.assertEqual(24.9, telemetry.report()["battery_voltage"])

        self.assertTrue(adapter.ok())
        adapter.shutdown()
        self.assertFalse(adapter.ok())
        self.assertTrue(node.destroyed)


class SimAdapterTest(unittest.TestCase):
    def test_integrates_the_last_velocity_until_told_otherwise(self):
        now = [0.0]
        telemetry = core.TelemetryCache(1.0, clock=lambda: now[0])
        sim = bridge.SimAdapter(telemetry, battery_voltage=24.0, clock=lambda: now[0])
        sim.publish_velocity(0.3, 0.0)
        now[0] = 2.0
        sim.spin_once()
        report = telemetry.report()
        self.assertAlmostEqual(0.6, report["position"]["x"], places=3)
        self.assertEqual(24.0, report["battery_voltage"])
        sim.publish_velocity(0.0, 0.5)
        now[0] = 2.0 + math.pi / 0.5  # half a turn at 0.5 rad/s
        sim.spin_once()
        self.assertAlmostEqual(math.pi, abs(telemetry.report()["position"]["yaw"]), places=3)
        self.assertTrue(sim.ok())

    def test_no_voltage_unless_configured(self):
        telemetry = core.TelemetryCache(1.0)
        bridge.SimAdapter(telemetry)
        self.assertIsNone(telemetry.report()["battery_voltage"])


class StartupTest(unittest.TestCase):
    def test_ros_version_detection(self):
        with mock.patch.dict(os.environ, {"ROS_VERSION": "1"}):
            self.assertEqual("1", bridge.detect_ros())
        with mock.patch.dict(os.environ, {"ROS_VERSION": ""}):
            with mock.patch.dict(sys.modules, fake_rclpy()):
                self.assertEqual("2", bridge.detect_ros())
            with mock.patch.dict(sys.modules, {"rclpy": None, "rospy": fake_rospy()}):
                self.assertEqual("1", bridge.detect_ros())
            with mock.patch.dict(sys.modules, {"rclpy": None, "rospy": None}):
                with self.assertRaises(core.ConfigError):
                    bridge.detect_ros()

    def test_command_line_overrides_the_config_file(self):
        try:
            import yaml  # noqa: F401
        except ImportError:
            self.skipTest("PyYAML not installed")
        shipped = bridge.build_config(bridge.parse_args([]))
        self.assertEqual(core.load_config(), shipped, "config.yaml documents exactly the defaults")
        args = bridge.parse_args(["--robot-id", "SCOUT-002", "--host", "10.0.0.5", "--port", "1884"])
        config = bridge.build_config(args)
        self.assertEqual(("SCOUT-002", "10.0.0.5", 1884), (config["robot_id"], config["mqtt"]["host"], config["mqtt"]["port"]))
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "robot.yaml")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("mqtt:\n  host: 192.168.1.20\nmotion:\n  linear_speed: 0.2\n")
            config = bridge.build_config(bridge.parse_args(["--config", path]))
            self.assertEqual(("192.168.1.20", 0.2), (config["mqtt"]["host"], config["motion"]["linear_speed"]))
            with self.assertRaises(core.ConfigError):
                bridge.build_config(bridge.parse_args(["--config", os.path.join(folder, "missing.yaml")]))

    def test_sim_adapter_is_only_used_when_asked_for(self):
        config = core.load_config()
        adapter = bridge.make_adapter("sim", config, core.TelemetryCache(1.0))
        self.assertEqual("sim", adapter.name)


if __name__ == "__main__":
    unittest.main()
