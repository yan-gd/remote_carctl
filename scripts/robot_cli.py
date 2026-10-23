# -*- coding: utf-8 -*-
"""Command-line remote for the robot API, for bring-up on the real car without a web page.

    python scripts/robot_cli.py --url http://127.0.0.1:8080 --user demo_operator status
    python scripts/robot_cli.py --user demo_operator hold FORWARD 1.5     # press 1.5 s, then STOP
    python scripts/robot_cli.py --user demo_monitor stop

"hold" behaves like a held button: it re-sends the command every 200 ms (keepalive) and sends
STOP when the time is up or on Ctrl+C. If this script dies, the robot's watchdog stops it.
The password is asked for interactively (or taken from PATROL_PASSWORD).
"""
import argparse
import getpass
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from patrol_client import ApiError, PatrolClient  # noqa: E402

KEEPALIVE_PERIOD = 0.2
MAX_HOLD_SECONDS = 10.0


def show(status, body):
    print("HTTP %s %s" % (status, json.dumps(body, ensure_ascii=False, indent=2)))


def hold(api, token, command, seconds):
    deadline = time.monotonic() + seconds
    sent = 0
    try:
        while time.monotonic() < deadline:
            status, body = api.control(command, token)
            sent += 1
            if status != 200:
                show(status, body)
                return 1
            time.sleep(KEEPALIVE_PERIOD)
    except KeyboardInterrupt:
        print("interrupted")
    finally:
        status, body = api.control("STOP", token)
        print("%s sent %d times, then STOP -> HTTP %s" % (command, sent, status))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--url", default="http://127.0.0.1:8080", help="backend base URL")
    parser.add_argument("--user", required=True)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("info", help="GET /api/v1/robot")
    sub.add_parser("status", help="GET /api/v1/robot/status")
    sub.add_parser("stop", help="send STOP once")
    press = sub.add_parser("hold", help="hold a motion command for N seconds")
    press.add_argument("command", choices=("FORWARD", "BACKWARD", "LEFT", "RIGHT"))
    press.add_argument("seconds", type=float)
    args = parser.parse_args()
    if args.action == "hold" and not 0 < args.seconds <= MAX_HOLD_SECONDS:
        parser.error("seconds must be in (0, %s]" % MAX_HOLD_SECONDS)

    api = PatrolClient(args.url)
    password = os.environ.get("PATROL_PASSWORD") or getpass.getpass("password for %s: " % args.user)
    try:
        token = api.login(args.user, password)
    except ApiError as error:
        print("login failed: %s" % error)
        return 1
    if args.action == "info":
        show(*api.request("GET", "/api/v1/robot", token=token))
    elif args.action == "status":
        show(*api.request("GET", "/api/v1/robot/status", token=token))
    elif args.action == "stop":
        show(*api.control("STOP", token))
    else:
        return hold(api, token, args.command, args.seconds)
    return 0


if __name__ == "__main__":
    sys.exit(main())
