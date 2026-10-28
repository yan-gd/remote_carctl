"""Shared HTTP/MQTT helpers. Records every interface case for the Chinese report."""

from __future__ import annotations

import base64
import json
import os
import re
import time
from queue import Empty, Queue
from typing import Any, Callable

import paho.mqtt.client as mqtt
import requests

BASE = os.environ.get("API_BASE", "http://127.0.0.1:18080").rstrip("/")
BASE_NO_MQTT = os.environ.get("API_BASE_NO_MQTT", "http://127.0.0.1:18081").rstrip("/")
MQTT_HOST = os.environ.get("MQTT_HOST", "127.0.0.1")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "11883"))
DEMO_PASSWORD = os.environ.get("PATROL_DEMO_PASSWORD", "TestDemo123!")
ADMIN_PASSWORD = os.environ.get("PATROL_ADMIN_PASSWORD", "TestAdmin123!")
CAMERA_KEY = os.environ.get("PATROL_CAMERA_DEVICE_KEY", "camera-device-key-0123456789abcdef")
MAPPING_KEY = os.environ.get("PATROL_MAPPING_DEVICE_KEY", "mapping-device-key-0123456789abcd")
JWT_SECRET_B64 = os.environ.get(
    "PATROL_JWT_SECRET",
    base64.b64encode(b"0123456789abcdef0123456789abcdef").decode("ascii"),
)
ROBOT = "SCOUT-001"

RESULTS: list[dict[str, Any]] = []
META: dict[str, Any] = {}
SESSION = requests.Session()
SESSION.headers["Accept"] = "application/json"

_JWT_RE = re.compile(r"eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+")


def jwt_key() -> bytes:
    return base64.b64decode(JWT_SECRET_B64)


def redact(text: str) -> str:
    text = _JWT_RE.sub("***", text)
    for secret in (DEMO_PASSWORD, ADMIN_PASSWORD, CAMERA_KEY, MAPPING_KEY):
        if secret:
            text = text.replace(secret, "***")
    return text


def snippet(resp: requests.Response | None) -> str:
    if resp is None:
        return ""
    ctype = resp.headers.get("Content-Type", "")
    if "json" not in ctype:
        raw = resp.content[:80]
        return redact(f"<{ctype or 'binary'} {len(resp.content)} bytes {raw[:24]!r}>")
    return redact(resp.text[:400])


def record(**row: Any) -> None:
    RESULTS.append(row)


def api(
    case_id: str,
    title: str,
    method: str,
    path: str,
    *,
    category: str,
    expected_status: int,
    expected_code: str | None = "SUCCESS",
    token: str | None = None,
    json_body: Any = None,
    data: bytes | None = None,
    headers: dict | None = None,
    params: dict | None = None,
    base: str | None = None,
    timeout: float = 20,
    message_contains: str | None = None,
    check: Callable[[requests.Response, Any], None] | None = None,
    interface: str | None = None,
) -> tuple[requests.Response | None, Any]:
    url = (base or BASE) + path
    hdrs = dict(headers or {})
    if token:
        hdrs["Authorization"] = "Bearer " + token
    started = time.perf_counter()
    resp = None
    body: Any = None
    error = ""
    try:
        resp = SESSION.request(
            method,
            url,
            json=json_body,
            data=data,
            headers=hdrs,
            params=params,
            timeout=timeout,
        )
        if "json" in resp.headers.get("Content-Type", ""):
            try:
                body = resp.json()
            except ValueError:
                body = None
    except requests.RequestException as exc:
        error = redact(str(exc))
    latency = round((time.perf_counter() - started) * 1000, 1)
    actual_code = body.get("code") if isinstance(body, dict) else None
    actual_message = body.get("message") if isinstance(body, dict) else ""
    ok = resp is not None and resp.status_code == expected_status and (
        expected_code is None or actual_code == expected_code
    )
    if ok and message_contains and message_contains not in (actual_message or ""):
        ok = False
        error = f"message 未包含 {message_contains!r}，实际 {actual_message!r}"
    if ok and check and resp is not None:
        try:
            check(resp, body)
        except AssertionError as exc:
            ok = False
            error = redact(str(exc))
    if resp is None and not error:
        error = "无响应"
        ok = False
    record(
        id=case_id,
        title=title,
        category=category,
        interface=interface or f"{method} {path}",
        method=method,
        path=path,
        expected_status=expected_status,
        expected_code=expected_code,
        actual_status=None if resp is None else resp.status_code,
        actual_code=actual_code,
        actual_message=redact(str(actual_message or ""))[:180],
        latency_ms=latency,
        snippet=snippet(resp),
        result="PASS" if ok else "FAIL",
        error=error,
    )
    if not ok:
        raise AssertionError(
            f"{case_id} 期望 HTTP {expected_status}/{expected_code}，"
            f"实际 {None if resp is None else resp.status_code}/{actual_code} {error} {snippet(resp)}"
        )
    return resp, body


def skip_case(case_id: str, title: str, interface: str, reason: str, category: str = "未执行") -> None:
    record(
        id=case_id,
        title=title,
        category=category,
        interface=interface,
        method="",
        path="",
        expected_status=None,
        expected_code=None,
        actual_status=None,
        actual_code=None,
        actual_message="",
        latency_ms=None,
        snippet="",
        result="SKIP",
        error=reason,
    )


def login(username: str, password: str, base: str | None = None) -> dict:
    resp = SESSION.post(
        (base or BASE) + "/api/v1/auth/login",
        json={"username": username, "password": password},
        timeout=20,
    )
    resp.raise_for_status()
    return resp.json()["data"]


def mqtt_client(client_id: str) -> mqtt.Client:
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION1, client_id=client_id)
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=10)
    return client


def publish(topic: str, payload: str | bytes, qos: int = 0) -> None:
    client = mqtt_client("api-test-pub-" + str(time.time_ns()))
    body = payload if isinstance(payload, bytes) else payload.encode("utf-8")
    client.loop_start()
    info = client.publish(topic, body, qos=qos, retain=False)
    info.wait_for_publish(timeout=5)
    time.sleep(0.15)
    client.loop_stop()
    client.disconnect()


def subscribe(topic: str, client_id: str) -> tuple[mqtt.Client, Queue]:
    queue: Queue = Queue()
    client = mqtt_client(client_id)

    def _on_message(_c, _u, msg):
        queue.put(msg)

    client.on_message = _on_message
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=10)
    client.subscribe(topic, qos=1)
    client.loop_start()
    time.sleep(0.2)
    return client, queue


def take(queue: Queue, timeout: float = 3) -> mqtt.MQTTMessage:
    try:
        return queue.get(timeout=timeout)
    except Empty as exc:
        raise AssertionError(f"{timeout}s 内未收到 MQTT 消息") from exc


def minimal_jpeg() -> bytes:
    return bytes([0xFF, 0xD8, 0xFF, 0xD9])


def route_body(name: str = "仓库东区路线", yaw: float | None = 0) -> dict:
    point = {"sequence": 0, "x": 1.5, "y": 2.0}
    if yaw is not None:
        point["yaw"] = yaw
    return {
        "name": name,
        "description": "接口测试路线，不驱动实车",
        "points": [point, {"sequence": 1, "x": 3.0, "y": 4.5}],
    }
