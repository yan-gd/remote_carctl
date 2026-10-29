"""Session fixtures: wait for the main broker link, then optionally start the sim bridge."""

from __future__ import annotations

import json
import os
import platform
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest
import requests

from harness import (
    ADMIN_PASSWORD,
    BASE,
    DEMO_PASSWORD,
    META,
    MQTT_PORT,
    RESULTS,
    login,
)

REPO = Path(__file__).resolve().parents[2]
BRIDGE_DIR = REPO / "robot-bridge"


@pytest.fixture(scope="session")
def tokens():
    users = {
        "admin": ADMIN_PASSWORD,
        "demo_operator": DEMO_PASSWORD,
        "demo_monitor": DEMO_PASSWORD,
        "demo_maintainer": DEMO_PASSWORD,
        "demo_viewer": DEMO_PASSWORD,
    }
    out = {}
    for name, password in users.items():
        data = login(name, password)
        out[name] = data["token"]
        out[name + "_id"] = data["user"]["id"]
        out[name + "_user"] = data["user"]
    return out


@pytest.fixture(scope="session", autouse=True)
def mqtt_link_ready(tokens):
    """Main server must be connected to the broker before offline-robot cases."""
    deadline = time.time() + 30
    last = None
    while time.time() < deadline:
        resp = requests.get(
            BASE + "/api/v1/robot",
            headers={"Authorization": "Bearer " + tokens["demo_operator"]},
            timeout=5,
        )
        last = resp.text[:300]
        if resp.status_code == 200 and resp.json()["data"]["mqttConnected"] and not resp.json()["data"]["online"]:
            return
        time.sleep(0.4)
    pytest.fail("主服务未在 30s 内连上 MQTT 且保持小车离线: " + str(last))


@pytest.fixture(scope="session")
def sim_bridge(tokens):
    config = (BRIDGE_DIR / "config.yaml").read_text(encoding="utf-8")
    config = config.replace("battery_voltage: null", "battery_voltage: 22.0", 1)
    path = Path("/tmp/scout-sim-api.yaml")
    path.write_text(config, encoding="utf-8")
    log = open("/tmp/scout-sim-api.log", "w", encoding="utf-8")
    proc = subprocess.Popen(
        [
            sys.executable,
            "scout_mqtt_bridge.py",
            "--ros",
            "sim",
            "--port",
            str(MQTT_PORT),
            "--config",
            str(path),
            "--log-level",
            "INFO",
        ],
        cwd=BRIDGE_DIR,
        stdout=log,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    deadline = time.time() + 20
    online = False
    while time.time() < deadline:
        if proc.poll() is not None:
            log.flush()
            pytest.fail("仿真桥退出，日志见 /tmp/scout-sim-api.log")
        resp = requests.get(
            BASE + "/api/v1/robot/status",
            headers={"Authorization": "Bearer " + tokens["demo_viewer"]},
            timeout=5,
        )
        if resp.status_code == 200 and resp.json()["data"]["online"]:
            data = resp.json()["data"]
            if data.get("batteryVoltage") == 22.0:
                online = True
                break
        time.sleep(0.3)
    if not online:
        log.flush()
        os.killpg(proc.pid, signal.SIGTERM)
        pytest.fail("仿真桥未在 20s 内上报心跳与电压，日志见 /tmp/scout-sim-api.log")
    yield proc
    if proc.poll() is None:
        os.killpg(proc.pid, signal.SIGTERM)
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
    log.close()


def pytest_sessionfinish(session, exitstatus):
    import shutil
    import subprocess as sp

    def cmd(args):
        try:
            return sp.check_output(args, text=True, stderr=sp.STDOUT).strip()
        except Exception as exc:
            return str(exc)

    META["code_under_test"] = os.environ.get("CODE_UNDER_TEST", "")
    META["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    META["python"] = platform.python_version()
    META["os"] = platform.platform()
    META["java"] = cmd(["java", "-version"])
    META["pytest_exitstatus"] = exitstatus
    passed = sum(1 for row in RESULTS if row["result"] == "PASS")
    failed = sum(1 for row in RESULTS if row["result"] == "FAIL")
    skipped = sum(1 for row in RESULTS if row["result"] == "SKIP")
    latencies = [row["latency_ms"] for row in RESULTS if isinstance(row.get("latency_ms"), (int, float))]
    summary = {
        "meta": META,
        "counts": {
            "total": len(RESULTS),
            "pass": passed,
            "fail": failed,
            "skip": skipped,
        },
        "latency_ms": {
            "min": min(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
            "avg": round(sum(latencies) / len(latencies), 1) if latencies else None,
        },
        "cases": RESULTS,
    }
    target = Path(os.environ.get("RESULTS_PATH", "/tmp/api-interface-results.json"))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    mirror = Path("/tmp/api-interface-results.json")
    if target.resolve() != mirror.resolve():
        shutil.copyfile(target, mirror)
