# -*- coding: utf-8 -*-
"""End-to-end check without the robot: backend JAR + MQTT broker + bridge (--ros sim).

    python scripts/e2e_sim.py [--broker-cmd "<command that runs a broker on --broker-port>"]

Prerequisites: ``mvn -DskipTests package`` in exp3/backend; a Python with paho-mqtt and PyYAML
for the bridge (--bridge-python, default: this interpreter); a broker that is already running,
or --broker-cmd so the script can also take the broker down and up again (outage scenario).

Every run uses a temporary SQLite file and random demo password; the normal database is never
touched. Results go to verification/e2e-report.json and to stdout (PASS/FAIL/SKIP per step).
"""
import argparse
import json
import os
import re
import secrets
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
EXP3 = HERE.parent
sys.path.insert(0, str(HERE))

from patrol_client import PatrolClient  # noqa: E402

JAR = EXP3 / "backend" / "start" / "target" / "start-1.0-SNAPSHOT.jar"
BRIDGE = EXP3 / "robot-bridge" / "scout_mqtt_bridge.py"
WINDOWS = os.name == "nt"


class Report(object):
    def __init__(self):
        self.steps = []

    def check(self, name, passed, **details):
        self.steps.append({"step": name, "result": "PASS" if passed else "FAIL", "details": details})
        print("%-4s %s %s" % ("PASS" if passed else "FAIL", name, _short(details)), flush=True)
        return passed

    def skip(self, name, reason):
        self.steps.append({"step": name, "result": "SKIP", "details": {"reason": reason}})
        print("SKIP %s (%s)" % (name, reason), flush=True)

    @property
    def failed(self):
        return [s for s in self.steps if s["result"] == "FAIL"]


def _short(details):
    text = json.dumps(details, ensure_ascii=False, default=str)
    return text if len(text) < 220 else text[:217] + "..."


def wait_until(predicate, timeout, interval=0.05):
    """(value, seconds) once predicate() is truthy, else (None, timeout). Errors count as falsy."""
    start = time.monotonic()
    while True:
        try:
            value = predicate()
        except Exception:  # backend still starting, connection refused, ...
            value = None
        elapsed = time.monotonic() - start
        if value:
            return value, round(elapsed, 3)
        if elapsed >= timeout:
            return None, round(elapsed, 3)
        time.sleep(interval)


def port_open(host, port):
    with socket.socket() as probe:
        probe.settimeout(0.5)
        return probe.connect_ex((host, port)) == 0


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def java_executable():
    """The real JVM binary: killing Oracle's javapath launcher would leave the JVM running."""
    exe = shutil.which("java")
    if exe is None:
        raise SystemExit("java not found on PATH")
    probe = subprocess.run([exe, "-XshowSettings:properties", "-version"], capture_output=True, timeout=30)
    match = re.search(r"^\s*java\.home = (.+)$", (probe.stderr + probe.stdout).decode("utf-8", "replace"), re.M)
    if match:
        candidate = Path(match.group(1).strip()) / "bin" / ("java.exe" if WINDOWS else "java")
        if candidate.exists():
            return str(candidate)
    return exe


def spawn(command, log_path, env=None):
    log = open(log_path, "ab")
    flags = subprocess.CREATE_NEW_PROCESS_GROUP if WINDOWS else 0
    process = subprocess.Popen(
        command, stdout=log, stderr=subprocess.STDOUT, env=env, creationflags=flags, start_new_session=not WINDOWS
    )
    process.log_file = log
    return process


def kill_tree(process):
    """Hard kill, like a crash or pulled power: no goodbye message is sent."""
    if process is None:
        return
    if process.poll() is None:
        if WINDOWS:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)], capture_output=True)
        else:
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(10)
    process.log_file.close()


def interrupt(process):
    """Polite stop (Ctrl+C / Ctrl+Break), letting the bridge stop the robot and say goodbye."""
    process.send_signal(signal.CTRL_BREAK_EVENT if WINDOWS else signal.SIGINT)


class Scenario(object):
    def __init__(self, args):
        self.args = args
        self.report = Report()
        self.work = Path(tempfile.mkdtemp(prefix="exp3-e2e-"))
        self.password = "E2e-" + secrets.token_urlsafe(12)
        self.broker = self.backend = self.bridge = None
        self.api = None
        self.tokens = {}

    # --- processes -------------------------------------------------------------------------

    def start_broker(self):
        command = self.args.broker_cmd if WINDOWS else shlex.split(self.args.broker_cmd)
        self.broker = spawn(command, self.work / "broker.log")
        _, seconds = wait_until(lambda: port_open(self.args.broker_host, self.args.broker_port), 20)
        return seconds

    def start_backend(self):
        port = free_port()
        env = dict(os.environ, PATROL_DEMO_PASSWORD=self.password)
        self.backend = spawn(
            [
                java_executable(),
                "-jar",
                str(self.args.jar),
                "--server.port=%d" % port,
                "--patrol.database.path=%s" % (self.work / "e2e.db"),
                "--patrol.demo.enabled=true",
                "--patrol.mqtt.broker-url=tcp://%s:%d" % (self.args.broker_host, self.args.broker_port),
                "--patrol.mqtt.reconnect-interval-ms=1000",
                "--patrol.robot.low-battery-voltage=22.0",
                "--spring.main.banner-mode=off",
            ],
            self.work / "backend.log",
            env,
        )
        self.api = PatrolClient("http://127.0.0.1:%d" % port)
        ok, seconds = wait_until(lambda: self.api.request("GET", "/v3/api-docs")[0] == 200, 120, 0.5)
        if not ok:
            raise SystemExit("backend did not start; see %s" % (self.work / "backend.log"))
        return seconds

    def start_bridge(self):
        config = self.work / "bridge.yaml"
        # The simulated chassis reports 21.8 V, below the 22.0 V threshold given to the backend.
        config.write_text("robot_id: SCOUT-001\nsim:\n  battery_voltage: 21.8\n", encoding="utf-8")
        self.bridge = spawn(
            [
                self.args.bridge_python,
                str(BRIDGE),
                "--ros",
                "sim",
                "--config",
                str(config),
                "--host",
                self.args.broker_host,
                "--port",
                str(self.args.broker_port),
            ],
            self.work / "bridge.log",
        )

    # --- API helpers -------------------------------------------------------------------------

    def status(self):
        return self.api.robot_status(self.tokens["viewer"])

    def control(self, command, who="operator"):
        status, body = self.api.control(command, self.tokens[who])
        return status, (body or {}).get("code"), (body or {}).get("data")

    def alarms(self, title):
        _, body = self.api.request("GET", "/api/v1/alarms?size=100", token=self.tokens["viewer"])
        return [a for a in body["data"]["items"] if a["title"] == title]

    def online(self):
        return self.status()["online"]

    # --- the scenario ------------------------------------------------------------------------

    def run(self):
        r = self.report
        if self.args.broker_cmd:
            if port_open(self.args.broker_host, self.args.broker_port):
                raise SystemExit("port %d is busy: stop that broker or drop --broker-cmd" % self.args.broker_port)
            r.check("broker started", True, seconds=self.start_broker())
        elif not port_open(self.args.broker_host, self.args.broker_port):
            raise SystemExit("no broker on %s:%d (start one, or pass --broker-cmd)" % (self.args.broker_host, self.args.broker_port))

        r.check("backend started", True, seconds=self.start_backend())
        for role in ("operator", "monitor", "maintainer", "viewer"):
            self.tokens[role] = self.api.login("demo_" + role, self.password)
        r.check("demo accounts can log in", True)

        self.start_bridge()
        up, seconds = wait_until(self.online, 20)
        r.check("bridge heartbeat brings the robot online", bool(up), seconds=seconds)
        s, seconds = wait_until(lambda: self.status() if self.status()["telemetryAt"] else None, 3)
        s = s or self.status()
        r.check(
            "status shows only real or null telemetry",
            s["mqttConnected"] and s["chassisConnected"] is True and s["batteryPct"] is None
            and s["batteryVoltage"] == 21.8 and s["motionState"] == "STOPPED"
            and s["position"]["x"] is not None,
            first_status_after_online_s=seconds,
            status=s,
        )
        low, _ = wait_until(lambda: self.alarms("电量过低"), 5)
        r.check("low battery voltage raises one WARNING alarm", low and len(low) == 1 and low[0]["level"] == "WARNING", alarms=low)

        # One command and its receipt.
        code, _, data = self.control("FORWARD")
        seq = (data or {}).get("seq")
        ack, seconds = wait_until(
            lambda: (self.status().get("lastAck") or {}).get("seq") == seq and self.status()["lastAck"]["result"] == "APPLIED", 3
        )
        r.check("FORWARD is accepted and acknowledged by the bridge", code == 200 and data["accepted"] and bool(ack), seq=seq, ack_seconds=seconds)
        time.sleep(2.0)  # watchdog stops it; the next press starts a new keepalive episode

        # Hold FORWARD for 2 s (a press re-sent every 200 ms), then let go.
        x0 = self.status()["position"]["x"]
        for _ in range(10):
            self.control("FORWARD")
            last_command = time.monotonic()
            time.sleep(0.2)
        stopped, _ = wait_until(lambda: self.status()["motionState"] == "STOPPED", 3)
        seen_stopped = round(time.monotonic() - last_command, 3)
        x1 = self.status()["position"]["x"]
        time.sleep(1.0)
        x2 = self.status()["position"]["x"]
        r.check("holding FORWARD moves the (simulated) chassis", x1 - x0 > 0.4, moved_m=round(x1 - x0, 3))
        r.check(
            "releasing stops it via the bridge watchdog",
            bool(stopped) and seen_stopped < 1.8 and abs(x2 - x1) < 0.005,
            stopped_seen_after_last_command_s=seen_stopped,
            includes="0.6 s watchdog + up to 0.5 s status period + polling",
            drift_after_stop_m=round(x2 - x1, 4),
        )
        _, body = self.api.request("GET", "/api/v1/control-logs?operation=ROBOT_CONTROL&size=100", token=self.tokens["maintainer"])
        rows = [
            row for row in body["data"]["items"]
            if row["actorName"] == "demo_operator" and row["target"] == "robot:SCOUT-001:FORWARD" and row["result"] == "SUCCESS"
        ]
        r.check("11 FORWARD requests leave 2 control-log rows (keepalives not logged)", len(rows) == 2, rows=len(rows))

        # Roles and validation.
        r.check("MONITOR may send STOP", self.control("STOP", "monitor")[0] == 200)
        r.check("MONITOR may not move the robot", self.control("FORWARD", "monitor")[:2] == (403, "FORBIDDEN"))
        r.check("VIEWER may not control", self.control("STOP", "viewer")[0] == 403)
        r.check("unknown command is 400 INVALID_COMMAND", self.control("JUMP")[:2] == (400, "INVALID_COMMAND"))

        # Crash: the broker publishes the bridge's last will.
        kill_tree(self.bridge)
        down, seconds = wait_until(lambda: not self.online(), 6)
        r.check("killed bridge goes offline at once (MQTT last will)", bool(down) and seconds < 2.0, seconds=seconds)
        lwt, _ = wait_until(lambda: [a for a in self.alarms("小车离线") if "遗嘱" in a["description"]], 3)
        r.check("offline raises a CRITICAL alarm", bool(lwt) and lwt[0]["level"] == "CRITICAL", alarm=(lwt or [None])[0])
        r.check("offline robot refuses motion (409 ROBOT_OFFLINE)", self.control("FORWARD")[:2] == (409, "ROBOT_OFFLINE"))
        r.check("STOP is still accepted while offline", self.control("STOP")[0] == 200)

        self.start_bridge()
        up, seconds = wait_until(self.online, 20)
        r.check("restarted bridge comes back online", bool(up), seconds=seconds)

        # Planned exit: the bridge stops the robot and says goodbye itself.
        try:
            interrupt(self.bridge)
        except (OSError, ValueError) as error:
            r.skip("Ctrl+C exit announces SHUTDOWN", "cannot signal the bridge here: %s" % error)
            kill_tree(self.bridge)
        else:
            try:
                self.bridge.wait(15)
            except subprocess.TimeoutExpired:
                pass
            exit_code = self.bridge.poll()
            bye, _ = wait_until(lambda: [a for a in self.alarms("小车离线") if "正常退出" in a["description"]], 5)
            r.check(
                "Ctrl+C exit announces SHUTDOWN (WARNING alarm, not CRITICAL)",
                exit_code == 0 and bool(bye) and bye[0]["level"] == "WARNING",
                exit_code=exit_code,
            )
            kill_tree(self.bridge)
        self.start_bridge()
        wait_until(self.online, 20)

        # Broker outage and recovery of both MQTT clients.
        if not self.args.broker_cmd:
            r.skip("broker outage and reconnect", "needs --broker-cmd")
            return
        kill_tree(self.broker)
        lost, seconds = wait_until(lambda: not self.status()["mqttConnected"], 20)
        r.check("broker outage is detected by the backend", bool(lost), seconds=seconds)
        r.check("commands fail with 503 MQTT_UNAVAILABLE", self.control("STOP")[:2] == (503, "MQTT_UNAVAILABLE"))
        link, _ = wait_until(lambda: self.alarms("MQTT连接断开"), 3)
        r.check("broker outage raises one CRITICAL alarm", link is not None and len(link) == 1, alarms=link)
        self.start_broker()
        back, seconds = wait_until(lambda: self.status()["mqttConnected"] and self.online(), 30, 0.2)
        r.check("backend and bridge reconnect by themselves", bool(back), seconds=seconds)
        r.check("control works again after recovery", self.control("FORWARD")[0] == 200 and self.control("STOP")[0] == 200)

    def close(self):
        for process in (self.bridge, self.backend, self.broker):
            try:
                kill_tree(process)
            except Exception as error:  # report, keep cleaning up
                print("cleanup: %s" % error)


def bridge_runtime(python):
    probe = subprocess.run(
        [python, "-c", "import sys, paho.mqtt; print(sys.version.split()[0], paho.mqtt.__version__)"],
        capture_output=True,
        text=True,
    )
    return probe.stdout.strip() or probe.stderr.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jar", type=Path, default=JAR)
    parser.add_argument("--broker-host", default="127.0.0.1")
    parser.add_argument("--broker-port", type=int, default=1883)
    parser.add_argument("--broker-cmd", help="starts a broker on --broker-port; enables the outage scenario")
    parser.add_argument("--bridge-python", default=sys.executable, help="Python with paho-mqtt and PyYAML")
    parser.add_argument("--report", type=Path, default=EXP3 / "verification" / "e2e-report.json")
    args = parser.parse_args()
    if not args.jar.exists():
        raise SystemExit("%s missing: run mvn -DskipTests package in exp3/backend" % args.jar)
    if hasattr(sys.stdout, "reconfigure"):  # alarm texts are Chinese; keep them readable when piped
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    scenario = Scenario(args)
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    error = None
    try:
        scenario.run()
    except SystemExit:
        raise
    except Exception as exc:  # report what happened instead of a bare traceback
        error = "%s: %s" % (type(exc).__name__, exc)
        scenario.report.check("scenario finished without errors", False, error=error)
    finally:
        scenario.close()
    report = scenario.report
    result = {
        "started": started,
        "bridge_python_and_paho": bridge_runtime(args.bridge_python),
        "broker": args.broker_cmd or "%s:%d (external)" % (args.broker_host, args.broker_port),
        "passed": sum(1 for s in report.steps if s["result"] == "PASS"),
        "failed": len(report.failed),
        "skipped": sum(1 for s in report.steps if s["result"] == "SKIP"),
        "steps": report.steps,
        "logs": str(scenario.work),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n%d passed, %d failed, %d skipped -> %s" % (result["passed"], result["failed"], result["skipped"], args.report))
    if report.failed:
        print("process logs kept in %s" % scenario.work)
        return 1
    shutil.rmtree(scenario.work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
