"""Verify the packaged service in two real JVM processes, using a temporary SQLite file.
Run after mvn clean install: python scripts/verify_process_restart.py
Uses only the Python standard library. Never writes the normal data/exp2.db.
"""
from pathlib import Path
from contextlib import closing
import json
import os
import re
import secrets
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "start/target/start-1.0-SNAPSHOT.jar"
OUTPUT = ROOT / "verification"


def java_executable():
    """Return the real JVM binary, not the Windows javapath launcher.

    Oracle's javapath java.exe starts the JVM as a child process. Terminating
    that launcher leaves the JVM running, which keeps the temporary SQLite file
    and the packaged JAR locked and makes TemporaryDirectory cleanup fail.
    """
    exe = shutil.which("java")
    if exe is None:
        raise SystemExit("java not found on PATH")
    if os.name != "nt":
        return exe
    try:
        probe = subprocess.run(
            [exe, "-XshowSettings:properties", "-version"],
            capture_output=True,
            timeout=30,
        )
        settings = (probe.stderr or b"") + (probe.stdout or b"")
        match = re.search(
            r"^\s*java\.home = (.+)$",
            settings.decode("utf-8", "replace"),
            re.MULTILINE,
        )
        if match:
            candidate = Path(match.group(1).strip()) / "bin" / "java.exe"
            if candidate.exists():
                return str(candidate)
    except (OSError, subprocess.TimeoutExpired):
        pass
    home = os.environ.get("JAVA_HOME")
    if home:
        candidate = Path(home) / "bin" / "java.exe"
        if candidate.exists():
            return str(candidate)
    return exe

def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

def request(port, method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{port}" + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as response:
        return response.status, json.loads(response.read())

def launch(db, cwd, password, log):
    port = free_port()
    env = os.environ.copy()
    env.update(PATROL_DEMO_PASSWORD=password, PATROL_DEMO_ENABLED="true")
    # Test the persisted generated key rather than an inherited deployment secret.
    env.pop("PATROL_JWT_SECRET", None)
    # Persistence check only: no broker involved, so no MQTT link alarm in alarm_record.
    proc = subprocess.Popen([java_executable(), "-jar", str(JAR), f"--server.port={port}",
        f"--patrol.database.path={db}", "--server.address=127.0.0.1",
        "--patrol.mqtt.enabled=false"], cwd=cwd,
        env=env, stdout=log, stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError("Service exited; inspect verification process log")
            try:
                status, doc = request(port, "GET", "/v3/api-docs")
                if status == 200:
                    return proc, port, doc
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                time.sleep(0.25)
        raise RuntimeError("Service startup timed out")
    except BaseException:
        stop(proc)
        raise

def stop(proc):
    if os.name == "nt":
        # /T also covers launchers that spawn the JVM as a child.
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"],
                       capture_output=True, check=False)
    else:
        proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=10)

def main():
    if not JAR.exists():
        raise SystemExit("Run mvn clean install first")
    OUTPUT.mkdir(exist_ok=True)
    password = "Demo-" + secrets.token_hex(16)
    with tempfile.TemporaryDirectory(prefix="patrol-process-proof-") as temp:
        temp_root = Path(temp).resolve()
        assert temp_root.parent == Path(tempfile.gettempdir()).resolve()
        db = temp_root / "data/exp2.db"
        with (OUTPUT / "process-first.log").open("wb") as log:
            proc, port, doc = launch(db, ROOT, password, log)
            try:
                _, login = request(port, "POST", "/api/v1/auth/login", {"username": "demo_operator", "password": password})
                token = login["data"]["token"]
                status, created = request(port, "POST", "/api/v1/routes", {"name": "独立进程重启验证", "points": [{"sequence": 0, "x": 1.2, "y": 3.4}]}, token)
                assert status == 201
                route_id = created["data"]["id"]
                with closing(sqlite3.connect(db)) as conn:
                    row = conn.execute("SELECT name FROM patrol_route WHERE id=?", (route_id,)).fetchone()
                    assert row == ("独立进程重启验证",)
                    assert conn.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/swagger-ui/index.html") as response:
                    assert response.status == 200 and b"Swagger UI" in response.read()
            finally:
                stop(proc)
        with (OUTPUT / "process-second.log").open("wb") as log:
            proc, port, _ = launch(db, ROOT / "start", password, log)
            try:
                status, restored = request(port, "GET", f"/api/v1/routes/{route_id}", token=token)
                assert status == 200 and restored["data"]["name"] == "独立进程重启验证"
                assert restored["data"]["points"][0]["x"] == 1.2
                with closing(sqlite3.connect(db)) as conn:
                    assert conn.execute("SELECT count(*) FROM sys_user").fetchone()[0] == 4
                    assert conn.execute("SELECT count(*) FROM alarm_record").fetchone()[0] == 1
                    assert conn.execute("SELECT count(*) FROM control_log WHERE operation='ROUTE_CREATE'").fetchone()[0] == 1
            finally:
                stop(proc)
        operations = sum(len([m for m in path if m in {"get", "post", "put", "delete", "patch"}]) for path in doc["paths"].values())
        assert operations == 20  # 17 from exp2 + 3 robot operations
        result = {"result": "PASS", "operations": operations, "sqlite_crud": True, "wal": True,
            "process_restart_persistence": True, "jwt_survives_restart": True,
            "demo_seed_idempotent": True, "swagger_html": True,
            "first_working_directory": str(ROOT), "second_working_directory": str(ROOT / "start"),
            "database": "isolated temporary file (removed after verification)"}
    (OUTPUT / "process-proof.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))

if __name__ == "__main__":
    main()
