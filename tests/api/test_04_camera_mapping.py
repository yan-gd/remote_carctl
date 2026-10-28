"""摄像头二进制转发与 Web 建图设备交换。不依赖实车，设备侧用 HTTP 替身。"""

import base64
import threading
import time

import requests

from harness import BASE, CAMERA_KEY, MAPPING_KEY, api, minimal_jpeg


def test_camera_ingest_snapshot_and_stream(tokens):
    viewer = tokens["demo_viewer"]
    jpeg = minimal_jpeg()
    api(
        "CAM-01",
        "未配置密钥时的对照放在无 MQTT 实例；本实例密钥已启用",
        "GET",
        "/api/v1/camera/status",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["enabled"] is True and data["data"]["persistence"] == "NONE"),
    )
    api(
        "CAM-02",
        "未登录不能看摄像头状态",
        "GET",
        "/api/v1/camera/status",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )
    api(
        "CAM-03",
        "设备密钥错误",
        "PUT",
        "/api/v1/device/camera/frame",
        category="权限",
        expected_status=401,
        expected_code="DEVICE_UNAUTHORIZED",
        data=jpeg,
        headers={"Content-Type": "image/jpeg", "X-Device-Key": "wrong-key-but-long-enough-0123456789"},
    )
    api(
        "CAM-04",
        "缺少设备密钥",
        "PUT",
        "/api/v1/device/camera/frame",
        category="权限",
        expected_status=401,
        expected_code="DEVICE_UNAUTHORIZED",
        data=jpeg,
        headers={"Content-Type": "image/jpeg"},
    )
    api(
        "CAM-05",
        "不是 JPEG",
        "PUT",
        "/api/v1/device/camera/frame",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        data=b"not-a-jpeg",
        headers={"Content-Type": "image/jpeg", "X-Device-Key": CAMERA_KEY},
    )
    api(
        "CAM-06",
        "帧超过 384KiB",
        "PUT",
        "/api/v1/device/camera/frame",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        data=b"\xff\xd8" + b"\x00" * (384 * 1024) + b"\xff\xd9",
        headers={"Content-Type": "image/jpeg", "X-Device-Key": CAMERA_KEY},
        timeout=30,
        message_contains="384",
    )
    box = {}

    def _snapshot():
        box["resp"] = requests.get(
            BASE + "/api/v1/camera/snapshot",
            headers={"Authorization": "Bearer " + viewer},
            timeout=8,
        )

    thread = threading.Thread(target=_snapshot)
    thread.start()
    time.sleep(0.4)
    api(
        "CAM-07",
        "设备上传最小合法 JPEG",
        "PUT",
        "/api/v1/device/camera/frame",
        category="正常",
        expected_status=200,
        data=jpeg,
        headers={"Content-Type": "image/jpeg", "X-Device-Key": CAMERA_KEY},
        check=lambda _r, data: _ok(data["data"]["viewers"] >= 1),
    )
    thread.join(timeout=8)
    snap = box.get("resp")
    ok = snap is not None and snap.status_code == 200 and snap.content == jpeg and snap.headers["Content-Type"].startswith("image/jpeg")
    from harness import record

    record(
        id="CAM-08",
        title="观看者在 5 秒内拿到刚上传的快照",
        category="正常",
        interface="GET /api/v1/camera/snapshot",
        method="GET",
        path="/api/v1/camera/snapshot",
        expected_status=200,
        expected_code=None,
        actual_status=None if snap is None else snap.status_code,
        actual_code=None,
        actual_message="",
        latency_ms=None,
        snippet=f"<{len(snap.content) if snap is not None else 0} bytes>",
        result="PASS" if ok else "FAIL",
        error="" if ok else "快照未返回刚上传的 JPEG",
    )
    if not ok:
        raise AssertionError("快照未返回刚上传的 JPEG")
    api(
        "CAM-09",
        "上传后短时间内状态为在线",
        "GET",
        "/api/v1/camera/status",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["relay"]["online"] is True),
    )
    _stream_one_frame(viewer, jpeg)
    _viewer_limit(viewer)


def _stream_one_frame(token: str, jpeg: bytes) -> None:
    from harness import record

    got = {}

    def _read():
        resp = requests.get(
            BASE + "/api/v1/camera/stream",
            headers={"Authorization": "Bearer " + token},
            stream=True,
            timeout=(5, 4),
        )
        got["status"] = resp.status_code
        got["type"] = resp.headers.get("Content-Type", "")
        buf = b""
        try:
            for chunk in resp.iter_content(64):
                if not chunk:
                    break
                buf += chunk
                if len(buf) >= 4 and int.from_bytes(buf[:4], "big") == len(jpeg) and buf[4 : 4 + len(jpeg)] == jpeg:
                    got["frame"] = True
                    break
                if len(buf) > 32:
                    break
        finally:
            resp.close()

    thread = threading.Thread(target=_read)
    thread.start()
    time.sleep(0.4)
    requests.put(
        BASE + "/api/v1/device/camera/frame",
        data=jpeg,
        headers={"Content-Type": "image/jpeg", "X-Device-Key": CAMERA_KEY},
        timeout=5,
    )
    thread.join(timeout=6)
    ok = got.get("status") == 200 and "octet-stream" in got.get("type", "") and got.get("frame") is True
    record(
        id="CAM-10",
        title="登录用户读取长度前缀的实时帧",
        category="正常",
        interface="GET /api/v1/camera/stream",
        method="GET",
        path="/api/v1/camera/stream",
        expected_status=200,
        expected_code=None,
        actual_status=got.get("status"),
        actual_code=None,
        actual_message=got.get("type", ""),
        latency_ms=None,
        snippet="frame" if ok else str(got),
        result="PASS" if ok else "FAIL",
        error="" if ok else "流中未读到刚上传的 JPEG",
    )
    if not ok:
        raise AssertionError(f"视频流失败: {got}")
    api(
        "CAM-11",
        "未登录不能拉流",
        "GET",
        "/api/v1/camera/stream",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
        timeout=5,
    )


def _viewer_limit(token: str) -> None:
    holders = []

    def _hold(index: int):
        resp = requests.get(
            BASE + "/api/v1/camera/stream",
            headers={"Authorization": "Bearer " + token},
            stream=True,
            timeout=(5, 3),
        )
        holders.append(resp)
        try:
            next(resp.iter_content(8))
        except Exception:
            pass

    threads = [threading.Thread(target=_hold, args=(i,)) for i in range(8)]
    for thread in threads:
        thread.start()
    deadline = time.time() + 6
    viewers = -1
    while time.time() < deadline:
        resp = requests.get(
            BASE + "/api/v1/camera/status",
            headers={"Authorization": "Bearer " + token},
            timeout=5,
        )
        viewers = resp.json()["data"]["relay"]["viewers"]
        if viewers >= 8:
            break
        time.sleep(0.2)
    busy = requests.get(
        BASE + "/api/v1/camera/snapshot",
        headers={"Authorization": "Bearer " + token},
        timeout=5,
    )
    for resp in holders:
        resp.close()
    for thread in threads:
        thread.join(timeout=2)
    from harness import record

    ok = viewers >= 8 and busy.status_code == 503 and busy.json().get("code") == "CAMERA_BUSY"
    record(
        id="CAM-12",
        title="第 9 个观看连接返回 CAMERA_BUSY",
        category="边界",
        interface="GET /api/v1/camera/snapshot",
        method="GET",
        path="/api/v1/camera/snapshot",
        expected_status=503,
        expected_code="CAMERA_BUSY",
        actual_status=busy.status_code,
        actual_code=busy.json().get("code") if "json" in busy.headers.get("Content-Type", "") else None,
        actual_message=str(viewers),
        latency_ms=None,
        snippet=busy.text[:180],
        result="PASS" if ok else "FAIL",
        error="" if ok else f"viewers={viewers} body={busy.text[:180]}",
    )
    if not ok:
        raise AssertionError(f"观看上限未生效 viewers={viewers} status={busy.status_code} {busy.text[:180]}")


def test_mapping_exchange(tokens):
    operator, viewer = tokens["demo_operator"], tokens["demo_viewer"]
    api(
        "MAP-01",
        "尚无设备上报时建图离线",
        "GET",
        "/api/v1/mapping",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["online"] is False),
    )
    api(
        "MAP-02",
        "没有当前地图",
        "GET",
        "/api/v1/mapping/grid",
        category="异常",
        expected_status=503,
        expected_code="MAP_UNAVAILABLE",
        token=viewer,
    )
    api(
        "MAP-03",
        "查看者不能下发建图命令",
        "POST",
        "/api/v1/mapping/commands",
        category="权限",
        expected_status=403,
        expected_code="FORBIDDEN",
        token=viewer,
        json_body={"action": "STOP"},
    )
    api(
        "MAP-04",
        "设备离线时操作员命令被拒绝",
        "POST",
        "/api/v1/mapping/commands",
        category="异常",
        expected_status=503,
        expected_code="MAPPING_OFFLINE",
        token=operator,
        json_body={"action": "STOP"},
    )
    api(
        "MAP-05",
        "建图设备密钥错误",
        "POST",
        "/api/v1/device/mapping/exchange",
        category="权限",
        expected_status=401,
        expected_code="DEVICE_UNAUTHORIZED",
        json_body=_report(),
        headers={"X-Device-Key": "wrong-mapping-key-0123456789abcdef"},
    )
    api(
        "MAP-06",
        "非法建图模式",
        "POST",
        "/api/v1/device/mapping/exchange",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        json_body=_report(mode="FLYING"),
        headers={"X-Device-Key": MAPPING_KEY},
        message_contains="地图",
    )
    api(
        "MAP-07",
        "设备上报建图状态并取走空命令",
        "POST",
        "/api/v1/device/mapping/exchange",
        category="正常",
        expected_status=200,
        json_body=_report(),
        headers={"X-Device-Key": MAPPING_KEY},
        check=lambda _r, data: _ok(data["data"] is None),
    )
    api(
        "MAP-08",
        "建图状态变为在线",
        "GET",
        "/api/v1/mapping",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["online"] is True and data["data"]["robot"]["mode"] == "MAPPING"),
    )
    api(
        "MAP-09",
        "栅格会话不一致",
        "PUT",
        "/api/v1/device/mapping/grid",
        category="异常",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        json_body=_grid(session="other-session"),
        headers={"X-Device-Key": MAPPING_KEY},
    )
    api(
        "MAP-10",
        "栅格尺寸与编码不匹配",
        "PUT",
        "/api/v1/device/mapping/grid",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        json_body=_grid(cells=base64.b64encode(b"\x00\x00").decode()),
        headers={"X-Device-Key": MAPPING_KEY},
    )
    api(
        "MAP-11",
        "上传 1x1 栅格",
        "PUT",
        "/api/v1/device/mapping/grid",
        category="正常",
        expected_status=200,
        json_body=_grid(),
        headers={"X-Device-Key": MAPPING_KEY},
        check=lambda _r, data: _ok(data["data"] is None),
    )
    api(
        "MAP-12",
        "读取当前栅格",
        "GET",
        "/api/v1/mapping/grid",
        category="正常",
        expected_status=200,
        token=viewer,
        check=lambda _r, data: _ok(data["data"]["width"] == 1 and data["data"]["mapId"] == "live-map"),
    )
    api(
        "MAP-13",
        "未知建图动作",
        "POST",
        "/api/v1/mapping/commands",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=operator,
        json_body={"action": "DELETE"},
    )
    command = api(
        "MAP-14",
        "操作员提交 STOP 并排队给设备",
        "POST",
        "/api/v1/mapping/commands",
        category="正常",
        expected_status=200,
        token=operator,
        json_body={"action": "STOP"},
        check=lambda _r, data: _ok(data["data"]["request"]["action"] == "STOP" and data["data"]["session"] == "sess-1"),
    )[1]["data"]
    api(
        "MAP-15",
        "上一命令未完成时拒绝新命令",
        "POST",
        "/api/v1/mapping/commands",
        category="异常",
        expected_status=409,
        expected_code="BUSINESS_CONFLICT",
        token=operator,
        json_body={"action": "STOP"},
    )
    api(
        "MAP-16",
        "设备再次交换时取到待执行命令",
        "POST",
        "/api/v1/device/mapping/exchange",
        category="正常",
        expected_status=200,
        json_body=_report(),
        headers={"X-Device-Key": MAPPING_KEY},
        check=lambda _r, data: _ok(data["data"]["id"] == command["id"]),
    )
    api(
        "MAP-17",
        "设备回执成功后命令出队",
        "POST",
        "/api/v1/device/mapping/exchange",
        category="正常",
        expected_status=200,
        json_body=_report(commandId=command["id"], result="SUCCEEDED", message="stopped"),
        headers={"X-Device-Key": MAPPING_KEY},
        check=lambda _r, data: _ok(data["data"] is None),
    )
    api(
        "MAP-18",
        "保存地图名非法",
        "POST",
        "/api/v1/mapping/commands",
        category="边界",
        expected_status=400,
        expected_code="INVALID_ARGUMENT",
        token=operator,
        json_body={"action": "SAVE", "name": "仓库图"},
    )
    api(
        "MAP-19",
        "加载不存在的地图",
        "POST",
        "/api/v1/mapping/commands",
        category="异常",
        expected_status=404,
        expected_code="NOT_FOUND",
        token=operator,
        json_body={"action": "LOAD", "name": "missing"},
    )
    api(
        "MAP-20",
        "未登录读取建图状态",
        "GET",
        "/api/v1/mapping",
        category="权限",
        expected_status=401,
        expected_code="UNAUTHORIZED",
    )


def _report(mode="MAPPING", commandId=None, result=None, message=None):
    return {
        "session": "sess-1",
        "mode": mode,
        "busy": False,
        "mapId": "live-map",
        "maps": ["warehouse"],
        "pose": {"x": 0.0, "y": 0.0, "yaw": 0.0},
        "scan": [],
        "path": [],
        "commandId": commandId,
        "result": result,
        "message": message,
    }


def _grid(session="sess-1", cells=None):
    return {
        "session": session,
        "mapId": "live-map",
        "width": 1,
        "height": 1,
        "resolution": 0.05,
        "originX": 0.0,
        "originY": 0.0,
        "originYaw": 0.0,
        "cells": cells or base64.b64encode(b"\x00").decode(),
    }


def _ok(condition: bool) -> None:
    if not condition:
        raise AssertionError("响应字段与约定不符")
