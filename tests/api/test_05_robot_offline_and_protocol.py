"""MQTT 已连接但仿真桥尚未启动：离线拒绝、手动替身报文、控制载荷。"""

import json
import time

import requests

from harness import BASE, ROBOT, api, mqtt_client, publish, subscribe, take


def test_offline_then_manual_telemetry(tokens):
    operator, monitor, viewer = tokens["demo_operator"], tokens["demo_monitor"], tokens["demo_viewer"]
    api(
        "ROBOT-01",
        "无心跳时机器人离线但 MQTT 已连接",
        "GET",
        "/api/v1/robot",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(
            data["data"]["robotId"] == ROBOT
            and data["data"]["online"] is False
            and data["data"]["mqttConnected"] is True
            and "FORWARD" in data["data"]["commands"]
            and "STOP" in data["data"]["commands"]
        ),
    )
    api(
        "ROBOT-02",
        "离线时运动状态为 UNKNOWN 且不伪造遥测",
        "GET",
        "/api/v1/robot/status",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(
            data["data"]["online"] is False
            and data["data"]["motionState"] == "UNKNOWN"
            and             data["data"]["batteryPct"] is None
            and data["data"]["position"] == {"x": None, "y": None, "yaw": None}
        ),
    )
    api(
        "ROBOT-03",
        "离线时拒绝 FORWARD",
        "POST",
        "/api/v1/robot/control",
        category="异常",
        expected_status=409,
        expected_code="ROBOT_OFFLINE",
        token=operator,
        json_body={"command": "FORWARD"},
    )
    api(
        "ROBOT-04",
        "查看者不能停车",
        "POST",
        "/api/v1/robot/control",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=viewer,
        json_body={"command": "STOP"},
    )
    api(
        "ROBOT-05",
        "监控员不能前进",
        "POST",
        "/api/v1/robot/control",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=monitor,
        json_body={"command": "FORWARD"},
    )
    api(
        "ROBOT-06",
        "命令不在白名单",
        "POST",
        "/api/v1/robot/control",
        category="边界",
        expected_status=400,
        expected_code="INVALID_COMMAND",
        token=operator,
        json_body={"command": "forward"},
    )
    api(
        "ROBOT-07",
        "未知控制字段",
        "POST",
        "/api/v1/robot/control",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=operator,
        json_body={"command": "STOP", "speed": 9},
    )
    api(
        "ROBOT-08",
        "未登录控制",
        "POST",
        "/api/v1/robot/control",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
        json_body={"command": "STOP"},
    )
    client, queue = subscribe(f"robot/{ROBOT}/control", "api-test-control")
    try:
        stopped = api(
            "ROBOT-09",
            "离线时仍允许 STOP 并写入 MQTT",
            "POST",
            "/api/v1/robot/control",
            category="正常",
            expected_status=200,
            token=monitor,
            json_body={"command": "STOP"},
            check=lambda _r, data: _ok(data["data"]["accepted"] is True and data["data"]["command"] == "STOP"),
        )[1]["data"]
        message = take(queue, 3)
        payload = json.loads(message.payload)
        assert payload["command"] == "STOP"
        assert payload["robot_id"] == ROBOT
        assert payload["seq"] == stopped["seq"]
        assert message.retain is False
    finally:
        client.loop_stop()
        client.disconnect()

    good_status = json.dumps(
        {
            "robot_id": ROBOT,
            "motion_state": "STOPPED",
            "battery_pct": None,
            "battery_voltage": 25.4,
            "linear_velocity": 0,
            "angular_velocity": 0,
            "position": {"x": 1.5, "y": -2, "yaw": 0.25},
            "chassis_connected": True,
            "source": "sim",
        }
    )
    heartbeat = json.dumps({"robot_id": ROBOT, "online": True, "chassis_connected": True, "ros": "sim"})
    _burst([(f"robot/{ROBOT}/heartbeat", heartbeat), (f"robot/{ROBOT}/status", good_status)])
    deadline = time.time() + 5
    seen = None
    while time.time() < deadline:
        seen = requests.get(
            BASE + "/api/v1/robot/status",
            headers={"Authorization": "Bearer " + viewer},
            timeout=5,
        ).json()["data"]
        voltage = seen.get("batteryVoltage")
        position = seen.get("position") or {}
        if seen["online"] and voltage is not None and abs(voltage - 25.4) < 1e-6 and abs(position.get("x", 99) - 1.5) < 1e-6:
            break
        time.sleep(0.2)
    else:
        raise AssertionError(f"遥测未生效: {seen}")
    api(
        "ROBOT-10",
        "手动心跳与状态被状态接口读到",
        "GET",
        "/api/v1/robot/status",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(
            data["data"]["online"] is True
            and abs((data["data"]["batteryVoltage"] or 0) - 25.4) < 1e-6
            and abs(data["data"]["position"]["x"] - 1.5) < 1e-6
        ),
    )
    _burst(
        [
            (f"robot/{ROBOT}/status", good_status),
            (f"robot/{ROBOT}/heartbeat", heartbeat),
            (f"robot/{ROBOT}/status", b"not-json"),
            (f"robot/{ROBOT}/heartbeat", json.dumps({"robot_id": "OTHER", "online": True})),
        ]
    )
    api(
        "ROBOT-11",
        "畸形报文和他人 robot_id 被忽略，原遥测仍在",
        "GET",
        "/api/v1/robot/status",
        category="异常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(
            data["data"]["online"] is True
            and data["data"]["batteryVoltage"] is not None
            and abs(data["data"]["batteryVoltage"] - 25.4) < 1e-6
        ),
    )
    publish(
        f"robot/{ROBOT}/control/ack",
        json.dumps(
            {
                "robot_id": ROBOT,
                "seq": 1,
                "session": "not-this-backend",
                "command": "STOP",
                "result": "APPLIED",
            }
        ),
    )
    time.sleep(0.4)
    api(
        "ROBOT-12",
        "其他 session 的回执不覆盖 lastAck",
        "GET",
        "/api/v1/robot/status",
        category="边界",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["lastAck"] is None),
    )
    publish(
        f"robot/{ROBOT}/heartbeat",
        json.dumps({"robot_id": ROBOT, "online": True, "chassis_connected": True, "ros": "sim"}),
    )
    time.sleep(0.3)
    client, queue = subscribe(f"robot/{ROBOT}/control", "api-test-forward")
    try:
        moved = api(
            "ROBOT-13",
            "在线后操作员 FORWARD 进入控制主题",
            "POST",
            "/api/v1/robot/control",
            category="正常",
            expected_status=200,
            token=operator,
            json_body={"command": "FORWARD"},
            check=lambda _r, data: _ok(data["data"]["accepted"] is True and data["data"]["command"] == "FORWARD"),
        )[1]["data"]
        payload = json.loads(take(queue, 3).payload)
        assert payload["command"] == "FORWARD" and payload["seq"] == moved["seq"] and payload["session"]
    finally:
        client.loop_stop()
        client.disconnect()
    api(
        "ROBOT-14",
        "1.5 秒内重复 FORWARD 仍被接受",
        "POST",
        "/api/v1/robot/control",
        category="正常",
        expected_status=200,
        token=operator,
        json_body={"command": "FORWARD"},
        check=lambda _r, data: _ok(data["data"]["seq"] > moved["seq"]),
    )


def _burst(items: list[tuple[str, str | bytes]]) -> None:
    client = mqtt_client("api-test-burst-" + str(time.time_ns()))
    client.loop_start()
    for topic, payload in items:
        body = payload if isinstance(payload, bytes) else payload.encode("utf-8")
        info = client.publish(topic, body, qos=0, retain=False)
        info.wait_for_publish(timeout=5)
    time.sleep(0.25)
    client.loop_stop()
    client.disconnect()


def _ok(condition: bool) -> None:
    if not condition:
        raise AssertionError("响应字段与约定不符")
