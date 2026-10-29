"""第二实例关闭 MQTT 与设备密钥；文档与未挂载的示例控制器；无法在本机执行的接口。"""

import requests

from harness import BASE, BASE_NO_MQTT, META, api, login, skip_case


def test_mqtt_disabled_server():
    admin = login("admin", None and "" or _password("admin"), BASE_NO_MQTT)
    operator = login("demo_operator", _password("demo"), BASE_NO_MQTT)
    monitor = login("demo_monitor", _password("demo"), BASE_NO_MQTT)
    viewer = login("demo_viewer", _password("demo"), BASE_NO_MQTT)
    base = BASE_NO_MQTT
    api(
        "OFF-01",
        "关闭 MQTT 时机器人离线且 Broker 未连接",
        "GET",
        "/api/v1/robot",
        category="异常",
        expected_status=200,
        token=viewer["token"],
        base=base,
        check=lambda _r, data: _ok(data["data"]["online"] is False and data["data"]["mqttConnected"] is False),
    )
    api(
        "OFF-02",
        "关闭 MQTT 时运动命令返回 503",
        "POST",
        "/api/v1/robot/control",
        category="异常",
        expected_status=503,
        expected_code="MQTT_UNAVAILABLE",
        token=operator["token"],
        base=base,
        json_body={"command": "FORWARD"},
    )
    api(
        "OFF-03",
        "关闭 MQTT 时 STOP 同样不排队",
        "POST",
        "/api/v1/robot/control",
        category="异常",
        expected_status=503,
        expected_code="MQTT_UNAVAILABLE",
        token=monitor["token"],
        base=base,
        json_body={"command": "STOP"},
    )
    api(
        "OFF-04",
        "关闭 MQTT 时非法命令仍先返回 400",
        "POST",
        "/api/v1/robot/control",
        category="边界",
        expected_status=400,
        expected_code="INVALID_COMMAND",
        token=operator["token"],
        base=base,
        json_body={"command": "SPRINT"},
    )
    api(
        "OFF-05",
        "无权限角色在 MQTT 关闭时仍是 403",
        "POST",
        "/api/v1/robot/control",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=viewer["token"],
        base=base,
        json_body={"command": "STOP"},
    )
    api(
        "OFF-06",
        "导航开关打开但没有实时报告",
        "GET",
        "/api/v1/navigation",
        category="正常",
        expected_status=200,
        token=viewer["token"],
        base=base,
        check=lambda _r, data: _ok(data["data"]["enabled"] is True and data["data"]["ready"] is False),
    )
    api(
        "OFF-07",
        "MQTT 关闭时不能启动导航",
        "POST",
        "/api/v1/navigation/missions",
        category="异常",
        expected_status=503,
        expected_code="MQTT_UNAVAILABLE",
        token=operator["token"],
        base=base,
        json_body={"mapId": "map-test", "points": [{"x": 1, "y": 1, "yaw": 0}], "cycles": 1},
    )
    api(
        "OFF-08",
        "MQTT 关闭时取消导航失败且不假装成功",
        "POST",
        "/api/v1/navigation/cancel",
        category="异常",
        expected_status=503,
        expected_code="MQTT_UNAVAILABLE",
        token=monitor["token"],
        base=base,
    )
    api(
        "OFF-09",
        "未配置摄像头密钥时状态为禁用",
        "GET",
        "/api/v1/camera/status",
        category="异常",
        expected_status=200,
        token=viewer["token"],
        base=base,
        check=lambda _r, data: _ok(data["data"]["enabled"] is False),
    )
    api(
        "OFF-11",
        "未配置摄像头密钥时设备上传被拒绝",
        "PUT",
        "/api/v1/device/camera/frame",
        category="异常",
        expected_status=503,
        expected_code="CAMERA_DISABLED",
        base=base,
        data=b"\xff\xd8\xff\xd9",
        headers={"Content-Type": "image/jpeg", "X-Device-Key": "camera-device-key-0123456789abcdef"},
    )
    api(
        "OFF-12",
        "未配置建图密钥时命令不可用",
        "POST",
        "/api/v1/mapping/commands",
        category="异常",
        expected_status=503,
        expected_code="MAPPING_DISABLED",
        token=operator["token"],
        base=base,
        json_body={"action": "STOP"},
    )
    assert admin["user"]["username"] == "admin"


def test_snapshot_jpeg_accept_when_camera_disabled():
    viewer = login("demo_viewer", _password("demo"), BASE_NO_MQTT)
    api(
        "OFF-10",
        "未配置摄像头密钥且 Accept 为 JPEG 时，快照应返回 503 JSON",
        "GET",
        "/api/v1/camera/snapshot",
        category="异常",
        expected_status=503,
        expected_code="CAMERA_DISABLED",
        token=viewer["token"],
        base=BASE_NO_MQTT,
        headers={"Accept": "image/jpeg"},
    )


def test_snapshot_json_accept_when_camera_disabled():
    viewer = login("demo_viewer", _password("demo"), BASE_NO_MQTT)
    api(
        "OFF-13",
        "未配置摄像头密钥且 Accept 为 JSON 时，快照应返回 503 JSON",
        "GET",
        "/api/v1/camera/snapshot",
        category="异常",
        expected_status=503,
        expected_code="CAMERA_DISABLED",
        token=viewer["token"],
        base=BASE_NO_MQTT,
        headers={"Accept": "application/json"},
    )


def test_openapi_and_unmounted_samples(tokens):
    resp = requests.get(BASE + "/v3/api-docs", timeout=10)
    body = resp.json()
    paths = sorted(body.get("paths", {}))
    operations = sum(len([k for k in item if k in {"get", "post", "put", "patch", "delete"}]) for item in body["paths"].values())
    META["openapi_paths"] = paths
    META["openapi_operations"] = operations
    from harness import record

    required = [
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/me",
        "/api/v1/routes",
        "/api/v1/tasks",
        "/api/v1/alarms",
        "/api/v1/control-logs",
        "/api/v1/robot",
        "/api/v1/robot/status",
        "/api/v1/robot/control",
        "/api/v1/admin/users",
        "/api/v1/camera/status",
        "/api/v1/camera/stream",
        "/api/v1/camera/snapshot",
        "/api/v1/device/camera/frame",
        "/api/v1/mapping",
        "/api/v1/mapping/grid",
        "/api/v1/mapping/commands",
        "/api/v1/device/mapping/exchange",
        "/api/v1/device/mapping/grid",
        "/api/v1/navigation",
        "/api/v1/navigation/missions",
        "/api/v1/navigation/cancel",
    ]
    missing = [path for path in required if path not in body["paths"]]
    ok = resp.status_code == 200 and not missing and operations >= len(required)
    record(
        id="DOC-01",
        title="OpenAPI 列出当前控制器",
        category="正常",
        interface="GET /v3/api-docs",
        method="GET",
        path="/v3/api-docs",
        expected_status=200,
        expected_code=None,
        actual_status=resp.status_code,
        actual_code=None,
        actual_message=f"{operations} operations, {len(paths)} paths",
        latency_ms=None,
        snippet=",".join(missing) or "complete",
        result="PASS" if ok else "FAIL",
        error="" if ok else "缺少 " + ",".join(missing),
    )
    if not ok:
        raise AssertionError("OpenAPI 缺少路径: " + ",".join(missing))
    swagger = requests.get(BASE + "/swagger-ui.html", timeout=10, allow_redirects=False)
    ok = swagger.status_code in {200, 302, 303, 307}
    record(
        id="DOC-02",
        title="Swagger UI 可访问",
        category="正常",
        interface="GET /swagger-ui.html",
        method="GET",
        path="/swagger-ui.html",
        expected_status=200,
        expected_code=None,
        actual_status=swagger.status_code,
        actual_code=None,
        actual_message=swagger.headers.get("Location", ""),
        latency_ms=None,
        snippet=swagger.headers.get("Content-Type", ""),
        result="PASS" if ok else "FAIL",
        error="" if ok else "Swagger 未返回成功或重定向",
    )
    if not ok:
        raise AssertionError(swagger.status_code)
    api(
        "DOC-03",
        "已登录访问未挂载的示例 /helloworld",
        "GET",
        "/helloworld",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=tokens["admin"],
    )
    api(
        "DOC-04",
        "未登录访问未知路径先被认证拦截",
        "GET",
        "/customer",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )
    api(
        "DOC-05",
        "不存在的 /api/v1 路径",
        "GET",
        "/api/v1/does-not-exist",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=tokens["admin"],
    )


def test_not_executable_interfaces():
    skip_case(
        "SKIP-01",
        "WHEP 信令",
        "POST /webrtc/SCOUT-001/whep",
        "本机没有 nginx auth_request、MediaMTX、TLS 证书和 H.264 推流端，接口不在 Spring Boot 进程内。",
    )
    skip_case(
        "SKIP-02",
        "RTSPS 推流",
        "RTSPS :8322 path SCOUT-001",
        "MediaMTX 模板要求替换发布密码和服务器证书，且依赖 Xavier 硬件编码，本机未启动。",
    )
    skip_case(
        "SKIP-03",
        "ROS1/ROS2 与 CAN 底盘",
        "ROS /cmd_vel /odom /scout_status；CAN scout_base",
        "虚拟机没有 ROS、scout_msgs 和实车 CAN。运动链路用 robot-bridge --ros sim 替身覆盖。",
    )
    skip_case(
        "SKIP-04",
        "Web 建图的 ROS 地图进程",
        "scout_mapping.py / map_server",
        "建图 HTTP 交换已用设备替身覆盖。小车侧 map_server 与激光定位需要 ROS1，本机未执行。",
    )


def _password(kind: str) -> str:
    from harness import ADMIN_PASSWORD, DEMO_PASSWORD

    return ADMIN_PASSWORD if kind == "admin" else DEMO_PASSWORD


def _ok(condition: bool) -> None:
    if not condition:
        raise AssertionError("响应字段与约定不符")
