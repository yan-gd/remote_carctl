"""仿真桥替身：回执、低电量告警、看门狗，以及导航 MQTT。"""

import json
import time

from harness import ROBOT, api, publish, subscribe, take


def _nav(state, mode="map", frame="map", mission=None, obstacle=None, distance=None, map_id="map-test"):
    body = {
        "robot_id": ROBOT,
        "ready": True,
        "reason": "READY",
        "state": state,
        "map_id": map_id,
        "mode": mode,
        "frame": frame,
        "point_index": 0,
        "total_points": 1,
    }
    if mission:
        body["mission_id"] = mission
    if obstacle is not None:
        body["obstacle_detected"] = obstacle
    if distance is not None:
        body["obstacle_distance"] = distance
    for _ in range(3):
        publish(f"robot/{ROBOT}/navigation/status", json.dumps(body))
        time.sleep(0.15)


def test_sim_bridge_ack_battery_and_watchdog(tokens, sim_bridge):
    operator, viewer = tokens["demo_operator"], tokens["demo_viewer"]
    assert sim_bridge.poll() is None
    api(
        "SIM-01",
        "仿真桥心跳使机器人在线并上报 22V",
        "GET",
        "/api/v1/robot/status",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(
            data["data"]["online"] is True
            and abs(data["data"]["batteryVoltage"] - 22.0) < 1e-6
            and data["data"]["batteryPct"] is None
            and data["data"]["chassisConnected"] is True
            and data["data"]["motionState"] in {"STOPPED", "FORWARD", "BACKWARD", "LEFT", "RIGHT"}
        ),
    )
    deadline = time.time() + 6
    found = False
    while time.time() < deadline:
        listed = api(
            "SIM-02",
            "低于 24V 阈值产生电量告警",
            "GET",
            "/api/v1/alarms",
            category="正常",
            expected_status=200,
            token=viewer,
            params={"q": "电量过低", "status": "UNACKNOWLEDGED", "page": 1, "size": 20},
        )[1]
        if listed["data"]["total"] >= 1:
            found = True
            break
        time.sleep(0.4)
    from harness import RESULTS

    polls = [row for row in RESULTS if row["id"] == "SIM-02"]
    if polls:
        kept = polls[-1]
        if not found:
            kept["result"] = "FAIL"
            kept["error"] = "未查询到电量过低告警"
        RESULTS[:] = [row for row in RESULTS if row["id"] != "SIM-02"] + [kept]
    if not found:
        raise AssertionError("未查询到电量过低告警")

    moved = api(
        "SIM-03",
        "仿真桥接受 FORWARD",
        "POST",
        "/api/v1/robot/control",
        category="正常",
        expected_status=200,
        token=operator,
        json_body={"command": "FORWARD"},
        check=lambda _r, data: _ok(data["data"]["accepted"] is True),
    )[1]["data"]
    deadline = time.time() + 4
    ack = None
    while time.time() < deadline:
        ack = api(
            "SIM-04",
            "仿真桥回执出现在 lastAck",
            "GET",
            "/api/v1/robot/status",
            category="正常",
            expected_status=200,
            token=viewer,
        )[1]["data"].get("lastAck")
        if ack and ack.get("seq") == moved["seq"] and ack.get("result") == "APPLIED" and ack.get("command") == "FORWARD":
            break
        time.sleep(0.2)
    else:
        raise AssertionError(f"未看到匹配回执: {ack}")
    polls = [row for row in RESULTS if row["id"] == "SIM-04"]
    RESULTS[:] = [row for row in RESULTS if row["id"] != "SIM-04"] + [polls[-1]]

    time.sleep(1.3)
    api(
        "SIM-05",
        "停止发送运动命令后看门狗停车",
        "GET",
        "/api/v1/robot/status",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["motionState"] == "STOPPED"),
    )
    api(
        "SIM-06",
        "运维员不能控制底盘",
        "POST",
        "/api/v1/robot/control",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=tokens["demo_maintainer"],
        json_body={"command": "STOP"},
    )


def test_navigation_contract(tokens, sim_bridge):
    operator, monitor, viewer = tokens["demo_operator"], tokens["demo_monitor"], tokens["demo_viewer"]
    assert sim_bridge.poll() is None
    api(
        "NAV-01",
        "未登录不能查询导航",
        "GET",
        "/api/v1/navigation",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )
    api(
        "NAV-02",
        "查看者不能启动导航",
        "POST",
        "/api/v1/navigation/missions",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=viewer,
        json_body={"mapId": "map-test", "points": [{"x": 1, "y": 1, "yaw": 0}], "cycles": 1},
    )
    api(
        "NAV-03",
        "运维员不能取消导航",
        "POST",
        "/api/v1/navigation/cancel",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=tokens["demo_maintainer"],
    )
    _nav("IDLE")
    time.sleep(0.3)
    api(
        "NAV-04",
        "地图模式就绪",
        "GET",
        "/api/v1/navigation",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["enabled"] is True and data["data"]["ready"] is True and data["data"]["robot"]["mapId"] == "map-test"),
    )
    api(
        "NAV-05",
        "地图标识不一致",
        "POST",
        "/api/v1/navigation/missions",
        category="异常",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        token=operator,
        json_body={"mapId": "other-map", "points": [{"x": 1, "y": 1, "yaw": 0}], "cycles": 1},
    )
    api(
        "NAV-06",
        "轮次为 0",
        "POST",
        "/api/v1/navigation/missions",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=operator,
        json_body={"mapId": "map-test", "points": [{"x": 1, "y": 1, "yaw": 0}], "cycles": 0},
    )
    client, queue = subscribe(f"robot/{ROBOT}/navigation/command", "api-test-nav")
    control, controls = subscribe(f"robot/{ROBOT}/control", "api-test-nav-stop")
    try:
        _nav("IDLE")
        started = api(
            "NAV-07",
            "操作员提交地图目标",
            "POST",
            "/api/v1/navigation/missions",
            category="正常",
            expected_status=200,
            token=operator,
            json_body={"mapId": "map-test", "points": [{"x": 1.25, "y": 2.5, "yaw": 0.3}], "cycles": 2},
            check=lambda _r, data: _ok(data["data"]["accepted"] is True and data["data"]["missionId"]),
        )[1]["data"]
        payload = json.loads(take(queue, 4).payload)
        assert payload["order"]["missionId"] == started["missionId"]
        assert payload["order"]["points"][0]["x"] == 1.25
        assert payload["robot_id"] == ROBOT
        assert payload["seq"] == started["seq"]
        api(
            "NAV-08",
            "已有任务时拒绝再次启动",
            "POST",
            "/api/v1/navigation/missions",
            category="异常",
            expected_status=409,
            expected_code="BUSINESS_CONFLICT",
            token=operator,
            json_body={"mapId": "map-test", "points": [{"x": 1, "y": 1, "yaw": 0}], "cycles": 1},
        )
        api(
            "NAV-09",
            "监控员取消导航并发送 STOP",
            "POST",
            "/api/v1/navigation/cancel",
            category="正常",
            expected_status=200,
            token=monitor,
            check=lambda _r, data: _ok(data["data"]["accepted"] is True),
        )
        cancel = json.loads(take(queue, 4).payload)
        assert cancel["order"]["action"] == "CANCEL"
        stop = json.loads(take(controls, 4).payload)
        assert stop["command"] == "STOP"
    finally:
        client.loop_stop()
        client.disconnect()
        control.loop_stop()
        control.disconnect()

    _nav("IDLE", mode="relative", frame="odom", map_id="relative-session")
    time.sleep(0.3)
    api(
        "NAV-10",
        "相对目标距离小于 0.1 米",
        "POST",
        "/api/v1/navigation/missions",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=operator,
        json_body={"mapId": "relative-session", "points": [{"x": 0.01, "y": 0, "yaw": 0}], "cycles": 1},
    )
    api(
        "NAV-11",
        "相对导航不允许多点",
        "POST",
        "/api/v1/navigation/missions",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=operator,
        json_body={
            "mapId": "relative-session",
            "points": [{"x": 1, "y": 0, "yaw": 0}, {"x": 1, "y": 1, "yaw": 0}],
            "cycles": 1,
        },
    )
    api(
        "NAV-12",
        "3 米内相对目标可以提交",
        "POST",
        "/api/v1/navigation/missions",
        category="正常",
        expected_status=200,
        token=operator,
        json_body={"mapId": "relative-session", "points": [{"x": 2, "y": 1, "yaw": 0}], "cycles": 1},
        check=lambda _r, data: _ok(data["data"]["accepted"] is True),
    )
    api(
        "NAV-13",
        "取消相对任务",
        "POST",
        "/api/v1/navigation/cancel",
        category="正常",
        expected_status=200,
        token=operator,
    )
    _nav("ACTIVE", mission="obs-1", obstacle=True, distance=0.4)
    time.sleep(0.7)
    _nav("ACTIVE", mission="obs-1", obstacle=True, distance=0.4)
    deadline = time.time() + 4
    found = False
    while time.time() < deadline:
        listed = api(
            "NAV-14",
            "持续近距离障碍写入告警",
            "GET",
            "/api/v1/alarms",
            category="正常",
            expected_status=200,
            token=viewer,
            params={"q": "NAV_OBSTACLE", "page": 1, "size": 10},
        )[1]
        if listed["data"]["total"] >= 1 and listed["data"]["items"][0]["source"].endswith("NAV_OBSTACLE"):
            found = True
            break
        time.sleep(0.3)
    from harness import RESULTS

    polls = [row for row in RESULTS if row["id"] == "NAV-14"]
    if polls:
        kept = polls[-1]
        if not found:
            kept["result"] = "FAIL"
            kept["error"] = "未产生导航障碍告警"
        RESULTS[:] = [row for row in RESULTS if row["id"] != "NAV-14"] + [kept]
    if not found:
        raise AssertionError("未产生导航障碍告警")
    _nav("IDLE")


def _ok(condition: bool) -> None:
    if not condition:
        raise AssertionError("响应字段与约定不符")
