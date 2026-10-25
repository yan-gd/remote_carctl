# -*- coding: utf-8 -*-
"""Unit tests of the ROS-independent bridge core: python -m unittest discover -s tests"""
import json
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import scout_bridge_core as core  # noqa: E402

TOPIC = "robot/SCOUT-001/"


class FakeClock(object):
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


class FakeLink(object):
    def __init__(self):
        self.connected = True
        self.published = []
        self.subscriptions = []

    def subscribe(self, topic, qos):
        self.subscriptions.append((topic, qos))

    def publish(self, topic, payload, qos=0, wait=0.0):
        self.published.append((topic, json.loads(payload), qos))
        return True

    def messages(self, suffix):
        return [message for topic, message, _ in self.published if topic == TOPIC + suffix]


class FakeRos(object):
    def __init__(self):
        self.velocities = []

    def publish_velocity(self, linear, angular):
        self.velocities.append((linear, angular))


def control(command, seq=None, session=None, **extra):
    message = {"command": command}
    if seq is not None:
        message["seq"] = seq
    if session is not None:
        message["session"] = session
    message.update(extra)
    return json.dumps(message).encode("utf-8")


class BridgeTestCase(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.config = core.load_config()
        self.telemetry = core.TelemetryCache(self.config["telemetry"]["chassis_timeout"], self.clock)
        self.ros = FakeRos()
        self.link = FakeLink()
        self.bridge = core.BridgeCore(
            self.config, self.ros, self.telemetry, "test", self.clock, lambda: 1780022123.0
        )
        self.bridge.bind(self.link)

    def run_for(self, seconds, rate=20.0):
        """Control cycles for ``seconds`` of fake time; returns the velocities they published."""
        start = len(self.ros.velocities)
        for _ in range(int(round(seconds * rate))):
            self.clock.advance(1.0 / rate)
            self.bridge.step()
        return self.ros.velocities[start:]


class ConfigTest(unittest.TestCase):
    def test_defaults_are_valid_and_conservative(self):
        config = core.load_config()
        self.assertEqual("SCOUT-001", config["robot_id"])
        self.assertEqual(0.3, config["motion"]["linear_speed"])
        self.assertEqual(0.5, config["motion"]["angular_speed"])
        self.assertLessEqual(config["safety"]["watchdog_timeout"], 1.0)
        self.assertEqual("/cmd_vel", config["ros"]["cmd_vel_topic"])

    def test_overrides_are_merged(self):
        config = core.load_config({"mqtt": {"host": "10.0.0.2"}, "motion": {"linear_speed": 0.5}, "sim": None})
        self.assertEqual("10.0.0.2", config["mqtt"]["host"])
        self.assertEqual(1883, config["mqtt"]["port"])
        self.assertEqual(0.5, config["motion"]["linear_speed"])
        self.assertEqual(0.5, config["motion"]["angular_speed"])

    def test_unsafe_or_mistyped_values_are_rejected(self):
        bad = [
            {"motion": {"linear_speed": 1.5}},
            {"motion": {"angular_speed": 0}},
            {"motion": {"linear_speed": "fast"}},
            {"safety": {"watchdog_timeout": 1.2}},
            {"safety": {"watchdog_timeout": 0.05}},
            {"safety": {"watchdog_timeout": 0.1, "publish_rate": 5}},
            {"safety": {"stop_frames": 2.5}},
            {"robot_id": "a/b"},
            {"robot_id": "#"},
            {"robot_id": 7},
            {"mqtt": {"port": 0}},
            {"mqtt": {"port": "1883"}},
            {"mqtt": {"keepalive": True}},
            {"mqtt": {"host": " "}},
            {"unknown": 1},
            {"motion": {"linear_sped": 0.3}},
            {"motion": 5},
            {"sim": {"battery_voltage": "24"}},
            {"ros": {"cmd_vel_topic": ""}},
            ["not", "a", "mapping"],
        ]
        for overrides in bad:
            with self.assertRaises(core.ConfigError, msg=str(overrides)):
                core.load_config(overrides)

    def test_topics_follow_the_requirement_table(self):
        self.assertEqual(
            {
                "control": "robot/SCOUT-001/control",
                "status": "robot/SCOUT-001/status",
                "heartbeat": "robot/SCOUT-001/heartbeat",
                "ack": "robot/SCOUT-001/control/ack",
            },
            core.topics("SCOUT-001"),
        )


class MotionTest(BridgeTestCase):
    def test_each_command_maps_to_a_velocity_published_at_once(self):
        expected = {
            "FORWARD": (0.3, 0.0),
            "BACKWARD": (-0.3, 0.0),
            "LEFT": (0.0, 0.5),
            "RIGHT": (0.0, -0.5),
            "STOP": (0.0, 0.0),
        }
        for command, velocity in expected.items():
            self.bridge.on_control(control(command))
            self.assertEqual(velocity, self.ros.velocities[-1], command)
            self.assertEqual("STOPPED" if command == "STOP" else command, self.bridge.motion.state)
        self.assertEqual(5, len([a for a in self.link.messages("control/ack") if a["result"] == "APPLIED"]))

    def test_keepalives_keep_the_robot_moving(self):
        for _ in range(15):  # 3 s of a held button, one command per 200 ms
            self.bridge.on_control(control("FORWARD"))
            self.assertNotIn((0.0, 0.0), self.run_for(0.2))
        self.assertEqual("FORWARD", self.bridge.motion.state)
        self.assertEqual(0, self.bridge.motion.watchdog_trips)

    def test_watchdog_stops_within_the_timeout_then_goes_quiet(self):
        self.bridge.on_control(control("FORWARD"))
        self.assertEqual([(0.3, 0.0)] * 11, self.run_for(0.55), "still moving inside the 0.6 s window")
        after = self.run_for(2.0)
        moving = [v for v in after if v != (0.0, 0.0)]
        # 0.60 s itself is the boundary cycle; by 0.65 s it must have stopped (PER-006: < 1 s).
        self.assertLessEqual(len(moving), 1)
        self.assertEqual(moving, after[: len(moving)])
        self.assertEqual([(0.0, 0.0)] * 10, after[len(moving):], "ten zero frames, then /cmd_vel is left alone")
        self.assertEqual("STOPPED", self.bridge.motion.state)
        self.assertEqual(1, self.bridge.motion.watchdog_trips)
        self.assertEqual("STOPPED", self.bridge.status_message()["motion_state"])
        self.assertEqual(1, self.bridge.heartbeat_message()["watchdog_trips"])

    def test_stop_is_immediate_and_not_a_watchdog_trip(self):
        self.bridge.on_control(control("LEFT"))
        self.run_for(0.2)
        self.bridge.on_control(control("STOP"))
        self.assertEqual((0.0, 0.0), self.ros.velocities[-1])
        self.assertEqual([(0.0, 0.0)] * 10, self.run_for(1.0))
        self.assertEqual(0, self.bridge.motion.watchdog_trips)

    def test_an_idle_bridge_publishes_nothing(self):
        self.assertEqual([], self.run_for(2.0))

    def test_losing_mqtt_stops_the_robot(self):
        self.bridge.on_control(control("FORWARD"))
        self.bridge.on_disconnected()
        self.assertEqual((0.0, 0.0), self.ros.velocities[-1])
        self.assertEqual("STOPPED", self.bridge.motion.state)
        self.assertEqual([(0.0, 0.0)] * 10, self.run_for(1.0))

    def test_shutdown_stops_and_says_goodbye(self):
        self.bridge.on_control(control("FORWARD"))
        self.bridge.shutdown()
        self.assertEqual([(0.0, 0.0)] * 3, self.ros.velocities[-3:])
        goodbye = self.link.published[-1]
        self.assertEqual(TOPIC + "heartbeat", goodbye[0])
        self.assertEqual({"robot_id": "SCOUT-001", "online": False, "reason": "SHUTDOWN", "timestamp": 1780022123000}, goodbye[1])
        self.assertEqual(1, goodbye[2])

    def test_ros_failure_does_not_break_the_loop(self):
        def broken(linear, angular):
            raise RuntimeError("publisher gone")

        self.ros.publish_velocity = broken
        self.bridge.on_control(control("FORWARD"))
        self.run_for(1.0)
        self.assertEqual("STOPPED", self.bridge.motion.state)


class ProtocolTest(BridgeTestCase):
    def test_receipts_echo_seq_and_session(self):
        self.bridge.on_control(control("FORWARD", seq=5, session="s1"))
        self.assertEqual(
            {
                "robot_id": "SCOUT-001",
                "seq": 5,
                "session": "s1",
                "command": "FORWARD",
                "result": "APPLIED",
                "reason": None,
                "timestamp": 1780022123000,
            },
            self.link.messages("control/ack")[-1],
        )

    def test_unknown_commands_are_rejected_without_moving(self):
        for command in ("JUMP", "forward", 5, None):
            self.bridge.on_control(json.dumps({"command": command, "seq": 6}).encode())
            ack = self.link.messages("control/ack")[-1]
            self.assertEqual(("REJECTED", "INVALID_COMMAND", None), (ack["result"], ack["reason"], ack["command"]))
        self.assertEqual([], self.ros.velocities)

    def test_duplicates_and_reordered_messages_are_dropped(self):
        self.bridge.on_control(control("FORWARD", seq=1, session="a"))
        self.bridge.on_control(control("FORWARD", seq=1, session="a"))  # QoS 1 redelivery
        self.bridge.on_control(control("BACKWARD", seq=0, session="a"))  # late
        self.bridge.on_control(control("STOP", seq=2, session="a"))
        self.assertEqual([1, 2], [ack["seq"] for ack in self.link.messages("control/ack")])
        self.assertEqual([(0.3, 0.0), (0.0, 0.0)], self.ros.velocities)
        self.bridge.on_control(control("LEFT", seq=1, session="b"))  # backend restarted
        self.assertEqual((0.0, 0.5), self.ros.velocities[-1])

    def test_messages_without_numbering_are_accepted(self):
        self.bridge.on_control(control("FORWARD"))
        self.bridge.on_control(control("FORWARD"))
        self.assertEqual(2, len(self.link.messages("control/ack")))

    def test_malformed_messages_are_ignored(self):
        for payload in (
            b"not json",
            b"[1]",
            b"\xff\xfe",
            b"{" + b" " * 5000 + b"}",
            control("FORWARD", seq="1"),
            control("FORWARD", seq=-1),
            control("FORWARD", seq=True),
            control("FORWARD", seq=1, session=123),
            control("FORWARD", seq=1, session="x" * 65),
            None,
        ):
            self.bridge.on_control(payload)
        self.assertEqual([], self.ros.velocities)
        self.assertEqual([], self.link.messages("control/ack"))

    def test_retained_or_foreign_commands_are_ignored(self):
        self.bridge.on_control(control("FORWARD"), retained=True)
        self.bridge.on_control(control("FORWARD", robot_id="SCOUT-002"))
        self.assertEqual([], self.ros.velocities)
        self.bridge.on_control(control("FORWARD", robot_id="SCOUT-001"))
        self.assertEqual([(0.3, 0.0)], self.ros.velocities)

    def test_connecting_subscribes_and_announces_at_once(self):
        self.link.connected = False
        self.run_for(0.1)  # cycles before the broker answers publish nothing
        self.link.connected = True
        self.bridge.on_connected()
        self.assertEqual([(TOPIC + "control", 1), (TOPIC + "navigation/command", 1)], self.link.subscriptions)
        heartbeat = self.link.messages("heartbeat")[-1]
        self.assertTrue(heartbeat["online"])
        self.assertEqual("test", heartbeat["ros"])
        self.run_for(0.05)
        self.assertEqual(1, len(self.link.messages("status")), "status follows on the next cycle")

    def test_an_unreachable_broker_is_reported_periodically(self):
        self.link.connected = False
        with self.assertLogs("scout_bridge", level="WARNING") as logs:
            self.run_for(16.0)
        warnings = [line for line in logs.output if "no MQTT broker connection" in line]
        self.assertEqual(2, len(warnings), "after 5 s, then every 10 s")
        self.link.connected = True
        self.run_for(1.0)
        self.link.connected = False
        with self.assertRaises(AssertionError):  # the 5 s grace period starts over
            with self.assertLogs("scout_bridge", level="WARNING"):
                self.run_for(4.0)

    def test_nothing_is_published_while_disconnected(self):
        self.link.connected = False
        self.bridge.on_control(control("FORWARD"))
        self.run_for(1.0)
        self.bridge.shutdown()
        self.assertEqual([], self.link.published)
        self.assertEqual((0.0, 0.0), self.ros.velocities[-1])


class TelemetryTest(BridgeTestCase):
    def test_heartbeat_once_per_second_and_status_twice(self):
        self.run_for(3.0)
        self.assertEqual(3, len(self.link.messages("heartbeat")))
        self.assertEqual(6, len(self.link.messages("status")))

    def test_status_carries_measured_values_only(self):
        self.telemetry.update_chassis(0.29, 0.01, 25.3)
        self.telemetry.update_odometry(1.0, 2.0, 0.5, 0.3, 0.0)
        self.bridge.step()
        status = self.link.messages("status")[-1]
        self.assertEqual("STOPPED", status["motion_state"])
        self.assertIsNone(status["battery_pct"])
        self.assertEqual(25.3, status["battery_voltage"])
        self.assertEqual(0.29, status["linear_velocity"], "chassis feedback wins over odometry")
        self.assertEqual({"x": 1.0, "y": 2.0, "yaw": 0.5}, status["position"])
        self.assertTrue(status["chassis_connected"])
        self.assertEqual("test", status["source"])
        self.assertEqual(1780022123000, status["timestamp"])

    def test_stale_values_become_null(self):
        self.telemetry.update_chassis(0.2, 0.0, 25.0)
        self.telemetry.update_odometry(1.0, 1.0, 0.0)
        self.clock.advance(1.5)
        report = self.telemetry.report()
        self.assertEqual(
            {
                "battery_pct": None,
                "battery_voltage": None,
                "linear_velocity": None,
                "angular_velocity": None,
                "position": {"x": None, "y": None, "yaw": None},
            },
            report,
        )
        self.assertFalse(self.telemetry.chassis_connected())

    def test_odometry_velocity_is_the_fallback(self):
        self.telemetry.update_odometry(0.0, 0.0, 0.0, 0.25, -0.1)
        report = self.telemetry.report()
        self.assertEqual((0.25, -0.1), (report["linear_velocity"], report["angular_velocity"]))
        self.assertIsNone(report["battery_voltage"])

    def test_unobservable_chassis_is_unknown_not_disconnected(self):
        self.telemetry.chassis_supported = False
        self.assertIsNone(self.bridge.heartbeat_message()["chassis_connected"])
        self.assertIsNone(self.bridge.status_message()["chassis_connected"])

    def test_non_finite_values_are_dropped(self):
        self.telemetry.update_chassis(float("nan"), "x", float("inf"))
        report = self.telemetry.report()
        self.assertIsNone(report["linear_velocity"])
        self.assertIsNone(report["angular_velocity"])
        self.assertIsNone(report["battery_voltage"])
        self.assertTrue(self.telemetry.chassis_connected())

    def test_yaw_from_quaternion(self):
        self.assertAlmostEqual(0.0, core.yaw_from_quaternion(0, 0, 0, 1))
        half = math.sqrt(0.5)
        self.assertAlmostEqual(math.pi / 2, core.yaw_from_quaternion(0, 0, half, half))
        self.assertAlmostEqual(math.pi, abs(core.yaw_from_quaternion(0, 0, 1, 0)))


class FakeInfo(object):
    def __init__(self):
        self.rc = 0
        self.checks = 0

    def is_published(self):
        self.checks += 1
        return self.checks > 2


class FakeClient(object):
    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs
        self.calls = []
        self.credentials = None
        self.tls = "unset"

    def will_set(self, topic, payload, qos, retain):
        self.will = (topic, json.loads(payload), qos, retain)

    def username_pw_set(self, username, password):
        self.credentials = (username, password)

    def tls_set(self, ca_certs=None):
        self.tls = ca_certs

    def reconnect_delay_set(self, min_delay, max_delay):
        self.delays = (min_delay, max_delay)

    def connect_async(self, host, port, keepalive):
        self.calls.append(("connect_async", host, port, keepalive))

    def loop_start(self):
        self.calls.append(("loop_start",))

    def loop_stop(self):
        self.calls.append(("loop_stop",))

    def disconnect(self):
        self.calls.append(("disconnect",))

    def subscribe(self, topic, qos):
        self.calls.append(("subscribe", topic, qos))

    def publish(self, topic, payload, qos, retain):
        self.calls.append(("publish", topic, payload, qos, retain))
        self.last_info = FakeInfo()
        return self.last_info


class Paho1(object):
    Client = FakeClient


class Paho2(object):
    Client = FakeClient

    class CallbackAPIVersion(object):
        VERSION1 = "v1"
        VERSION2 = "v2"


class ReasonCode(object):
    def __init__(self, failure):
        self.is_failure = failure


class Message(object):
    def __init__(self, topic, payload, retain=0):
        self.topic, self.payload, self.retain = topic, payload, retain


class MqttLinkTest(unittest.TestCase):
    def make(self, paho, **mqtt):
        self.events = []
        config = core.load_config({"mqtt": mqtt})
        return core.MqttLink(
            config["mqtt"],
            "SCOUT-001",
            lambda: self.events.append("connected"),
            lambda: self.events.append("disconnected"),
            lambda payload, retained: self.events.append(("control", payload, retained)),
            paho=paho,
        )

    def test_paho2_client_and_last_will(self):
        link = self.make(Paho2)
        client = link._client
        self.assertEqual(("v2",), client.args)
        self.assertTrue(client.kwargs["clean_session"])
        self.assertTrue(client.kwargs["client_id"].startswith("scout-bridge-SCOUT-001-"))
        self.assertEqual(
            (TOPIC + "heartbeat", {"robot_id": "SCOUT-001", "online": False, "reason": "LWT"}, 1, False),
            client.will,
        )
        self.assertIsNone(client.credentials)
        self.assertEqual("unset", client.tls)
        self.assertEqual((1, 5), client.delays)

    def test_paho1_client_credentials_and_tls(self):
        link = self.make(Paho1, username="robot", password="secret", tls=True, ca_certs="/etc/ca.pem", client_id="car1")
        client = link._client
        self.assertEqual((), client.args)
        self.assertEqual("car1", client.kwargs["client_id"])
        self.assertEqual(("robot", "secret"), client.credentials)
        self.assertEqual("/etc/ca.pem", client.tls)

    def test_callbacks_accept_both_paho_signatures(self):
        link = self.make(Paho2)
        link._handle_connect(None, None, {}, ReasonCode(False), None)  # paho 2.x
        self.assertTrue(link.connected)
        link._handle_disconnect(None, None, {}, ReasonCode(False), None)
        self.assertFalse(link.connected)
        link._handle_connect(None, None, {}, 0)  # paho 1.x
        self.assertTrue(link.connected)
        link._handle_disconnect(None, None, 0)
        link._handle_connect(None, None, {}, 5)  # refused (not authorised)
        link._handle_connect(None, None, {}, ReasonCode(True), None)
        self.assertFalse(link.connected)
        self.assertEqual(["connected", "disconnected", "connected", "disconnected"], self.events)

    def test_only_control_messages_are_forwarded(self):
        link = self.make(Paho1)
        link._handle_message(None, None, Message(TOPIC + "control", b"{}", 1))
        link._handle_message(None, None, Message(TOPIC + "status", b"{}"))
        self.assertEqual([("control", b"{}", True)], self.events)

    def test_navigation_messages_keep_their_retained_flag_and_robot_scope(self):
        link = self.make(Paho1)
        received = []
        link.navigation_handler = lambda payload, retained: received.append((payload, retained))
        link._handle_message(None, None, Message(TOPIC + 'navigation/command', b'{"seq":1}', 1))
        link._handle_message(None, None, Message('robot/OTHER/navigation/command', b'{}'))
        link._handle_message(None, None, Message(TOPIC + 'navigation/status', b'{}'))
        self.assertEqual([(b'{"seq":1}', True)], received)
        self.assertEqual([], self.events)

    def test_publish_lifecycle(self):
        link = self.make(Paho1, host="10.1.1.1", port=1884, keepalive=7)
        client = link._client
        self.assertFalse(link.publish("t", "{}"))
        self.assertEqual([], [c for c in client.calls if c[0] == "publish"])
        link.start()
        self.assertEqual([("connect_async", "10.1.1.1", 1884, 7), ("loop_start",)], client.calls)
        link._handle_connect(None, None, {}, 0)
        link.subscribe("x", 1)
        self.assertTrue(link.publish("t", "{}", qos=1, wait=1.0))
        self.assertEqual(("publish", "t", "{}", 1, False), client.calls[-1])
        self.assertGreater(client.last_info.checks, 2, "waited for delivery")
        link.stop()
        self.assertEqual([("disconnect",), ("loop_stop",)], client.calls[-2:])


if __name__ == "__main__":
    unittest.main()
