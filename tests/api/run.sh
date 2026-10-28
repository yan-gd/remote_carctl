#!/usr/bin/env bash
# Start a local Mosquitto, two backend instances, then the pytest interface suite.
# The simulated robot is started by pytest after the offline cases. Hardware is not required.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

API_PORT="${API_PORT:-18080}"
API_PORT_NO_MQTT="${API_PORT_NO_MQTT:-18081}"
MQTT_PORT="${MQTT_PORT:-11883}"
export API_BASE="http://127.0.0.1:${API_PORT}"
export API_BASE_NO_MQTT="http://127.0.0.1:${API_PORT_NO_MQTT}"
export MQTT_HOST="${MQTT_HOST:-127.0.0.1}"
export MQTT_PORT

# Local fixture secrets. Do not reuse them in a deployment.
export PATROL_DEMO_PASSWORD="${PATROL_DEMO_PASSWORD:-TestDemo123!}"
export PATROL_ADMIN_PASSWORD="${PATROL_ADMIN_PASSWORD:-TestAdmin123!}"
export PATROL_CAMERA_DEVICE_KEY="${PATROL_CAMERA_DEVICE_KEY:-camera-device-key-0123456789abcdef}"
export PATROL_MAPPING_DEVICE_KEY="${PATROL_MAPPING_DEVICE_KEY:-mapping-device-key-0123456789abcd}"
export PATROL_JWT_SECRET="${PATROL_JWT_SECRET:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}"
export RESULTS_PATH="${RESULTS_PATH:-$ROOT/docs/test-report/last-run.json}"

JAR="$ROOT/backend/start/target/start-1.0-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "缺少 Maven，且不存在 $JAR" >&2
    exit 1
  fi
  (cd "$ROOT/backend" && mvn -B -DskipTests package)
fi

VENV="${VENV:-/tmp/remote-carctl-api-venv}"
if [[ ! -x "$VENV/bin/pytest" ]]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -r "$ROOT/tests/api/requirements.txt"
fi

# exec replaces the launcher shell, so the recorded pid is the JVM. Also clear
# leftovers from an interrupted run before binding the ports.
pkill -f 'start-1.0-SNAPSHOT.jar' 2>/dev/null || true
pkill -f 'scout_mqtt_bridge.py --ros sim' 2>/dev/null || true
pkill -x mosquitto 2>/dev/null || true
sleep 0.5

WORKDIR="$(mktemp -d /tmp/remote-carctl-api.XXXXXX)"
mkdir -p "$ROOT/docs/test-report"
PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT

cat >"$WORKDIR/mosquitto.conf" <<EOF
listener ${MQTT_PORT} 127.0.0.1
allow_anonymous true
persistence false
log_dest file $WORKDIR/mosquitto.log
EOF
mosquitto -c "$WORKDIR/mosquitto.conf" &
PIDS+=("$!")

wait_http() {
  local url="$1"
  for _ in $(seq 1 60); do
    if curl -sf "$url" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "服务未就绪: $url" >&2
  return 1
}

start_backend() {
  local name="$1" port="$2" mqtt_enabled="$3" db="$4" camera="$5" mapping="$6"
  local logfile="/tmp/remote-carctl-${name}.log"
  (
    cd "$ROOT/backend"
    export PORT="$port" \
      BIND_ADDRESS="127.0.0.1" \
      PATROL_DB_PATH="$db" \
      PATROL_MQTT_ENABLED="$mqtt_enabled" \
      PATROL_MQTT_URL="tcp://127.0.0.1:${MQTT_PORT}" \
      PATROL_ROBOT_ID="SCOUT-001" \
      PATROL_DEMO_ENABLED="true" \
      PATROL_DEMO_PASSWORD="$PATROL_DEMO_PASSWORD" \
      PATROL_ADMIN_PASSWORD="$PATROL_ADMIN_PASSWORD" \
      PATROL_JWT_SECRET="$PATROL_JWT_SECRET" \
      PATROL_NAVIGATION_ENABLED="true" \
      PATROL_LOW_BATTERY_VOLTAGE="24" \
      PATROL_CAMERA_DEVICE_KEY="$camera" \
      PATROL_MAPPING_DEVICE_KEY="$mapping"
    exec java -Xmx384m -jar "$JAR"
  ) >"$logfile" 2>&1 &
  PIDS+=("$!")
}

start_backend main "$API_PORT" true "$WORKDIR/main.db" "$PATROL_CAMERA_DEVICE_KEY" "$PATROL_MAPPING_DEVICE_KEY"
start_backend nomqtt "$API_PORT_NO_MQTT" false "$WORKDIR/nomqtt.db" "" ""
wait_http "$API_BASE/v3/api-docs" || { tail -n 80 /tmp/remote-carctl-main.log; exit 1; }
wait_http "$API_BASE_NO_MQTT/v3/api-docs" || { tail -n 80 /tmp/remote-carctl-nomqtt.log; exit 1; }

set +e
"$VENV/bin/pytest" "$ROOT/tests/api" -q
STATUS=$?
set -e
"$VENV/bin/python" "$ROOT/tests/api/render_report.py" "$RESULTS_PATH" "$ROOT/docs/test-report/接口测试报告.md"
echo "结果: $RESULTS_PATH"
echo "报告: $ROOT/docs/test-report/接口测试报告.md"
exit "$STATUS"
