#!/usr/bin/env bash
# Foreground launcher for this Xavier: ROS1 Melodic + Conda Python 3.7.
# Run as agilex: bash start-scout.sh [--no-camera]
set -eo pipefail

SCOUT_DIR=/home/agilex/Desktop/remote_carctl/robot-bridge
SCOUT_PY2=/usr/bin/python2.7
SCOUT_PY3=/home/agilex/archiconda3/bin/python3
SCOUT_CAMERA=1
SCOUT_CLIENTS=()
SCOUT_DRIVERS=()

case "${1:-}" in
    '') ;;
    --no-camera) SCOUT_CAMERA=0 ;;
    *) echo 'Usage: bash start-scout.sh [--no-camera]' >&2; exit 2 ;;
esac

if [[ "$(id -un)" != agilex ]]; then
    echo 'Run as agilex, without sudo: bash start-scout.sh' >&2
    exit 1
fi
test -x "$SCOUT_PY2"
test -x "$SCOUT_PY3"
test -r "$SCOUT_DIR/config.yaml"
test -r /opt/ros/melodic/setup.bash
test -r /home/agilex/catkin_ws/devel/setup.bash

sudo -v
SCOUT_ENV="$HOME/.config/remote-carctl/camera.env"
if (( SCOUT_CAMERA )); then
    test -r "$SCOUT_DIR/scout_camera_uplink.py"
    if [[ ! -r "$SCOUT_ENV" ]]; then
        sudo -n test -s /etc/remote-carctl/camera.env
    fi
fi

# Stop/disable only the five units used by the previous unified setup.
# The old files, robot YAML and camera credentials remain in place.
sudo -n systemctl disable --now scout-camera-uplink scout-d435 \
    scout-mqtt-bridge scout-base scout-can

source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
export ROS_MASTER_URI=http://localhost:11311
export PYTHONUNBUFFERED=1
cd "$SCOUT_DIR"

scout_cleanup() {
    trap - EXIT INT TERM
    echo 'Stopping camera uplink and MQTT bridge...'
    for SCOUT_PID in "${SCOUT_CLIENTS[@]}"; do
        kill -TERM "$SCOUT_PID" 2>/dev/null || true
    done
    # A client stuck inside rospy.init_node may not respond to its own handler.
    for (( SCOUT_N=0; SCOUT_N<30; SCOUT_N++ )); do
        SCOUT_ALIVE=0
        for SCOUT_PID in "${SCOUT_CLIENTS[@]}"; do
            if kill -0 "$SCOUT_PID" 2>/dev/null; then SCOUT_ALIVE=1; fi
        done
        if (( SCOUT_ALIVE == 0 )); then break; fi
        sleep 0.2
    done
    for SCOUT_PID in "${SCOUT_CLIENTS[@]}"; do
        kill -KILL "$SCOUT_PID" 2>/dev/null || true
        wait "$SCOUT_PID" 2>/dev/null || true
    done
    # Let roslaunch shut down its own child processes before stopping its master.
    echo 'Stopping ROS drivers...'
    for (( SCOUT_N=${#SCOUT_DRIVERS[@]}-1; SCOUT_N>=0; SCOUT_N-- )); do
        SCOUT_PID=${SCOUT_DRIVERS[SCOUT_N]}
        kill -INT "$SCOUT_PID" 2>/dev/null || true
        wait "$SCOUT_PID" 2>/dev/null || true
    done
}
trap scout_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

SCOUT_FLAGS=$(cat /sys/class/net/can0/flags)
if (( SCOUT_FLAGS & 1 )); then
    if ! ip -details link show can0 | grep -Eq 'bitrate[[:space:]]+500000([[:space:]]|$)'; then
        echo 'can0 is already UP with a different bitrate; stop and check it.' >&2
        exit 1
    fi
else
    sudo -n ip link set can0 up type can bitrate 500000
fi

env -u PATROL_CAMERA_DEVICE_KEY "$SCOUT_PY2" /opt/ros/melodic/bin/roslaunch \
    scout_base scout_mini_base.launch &
SCOUT_DRIVERS+=("$!")

# rostopic exits immediately when master is not ready; retry, rather than
# treating that initial startup race as a chassis failure.
SCOUT_DEADLINE=$((SECONDS + 45))
until timeout -k 1s 3s "$SCOUT_PY2" /opt/ros/melodic/bin/rosnode list >/dev/null 2>&1; do
    if ! kill -0 "${SCOUT_DRIVERS[0]}" 2>/dev/null || (( SECONDS >= SCOUT_DEADLINE )); then
        echo 'ROS Master did not become ready. See the driver output above.' >&2
        exit 1
    fi
    sleep 1
done

"$SCOUT_PY3" -u scout_mqtt_bridge.py --ros 1 --config config.yaml &
SCOUT_CLIENTS+=("$!")

if (( SCOUT_CAMERA )); then
    env -u PATROL_CAMERA_DEVICE_KEY "$SCOUT_PY2" /opt/ros/melodic/bin/roslaunch \
        realsense2_camera rs_camera.launch &
    SCOUT_DRIVERS+=("$!")
    (
        # Read the existing secret only in the uploader process, so roslaunch
        # does not inherit it and write it to its environment log.
        set -a
        if [[ -r "$SCOUT_ENV" ]]; then
            source "$SCOUT_ENV"
        else
            source <(sudo -n cat /etc/remote-carctl/camera.env)
        fi
        set +a
        : "${PATROL_CAMERA_DEVICE_KEY:?Missing camera device key}"
        exec "$SCOUT_PY3" -u scout_camera_uplink.py \
            --server "${CAMERA_SERVER:?Missing CAMERA_SERVER}" \
            --ros-topic "${CAMERA_ROS_TOPIC:-/camera/color/image_raw/compressed}" \
            --fps "${CAMERA_FPS:-4}"
    ) &
    SCOUT_CLIENTS+=("$!")
fi

echo 'Processes launched. Keep this terminal open; Ctrl+C stops them.'
echo 'Verify robot online status and the live camera image in the Web page.'
while sleep 2; do
    for SCOUT_PID in "${SCOUT_DRIVERS[@]}" "${SCOUT_CLIENTS[@]}"; do
        if ! kill -0 "$SCOUT_PID" 2>/dev/null; then
            echo "Process $SCOUT_PID exited. Stopping the other processes." >&2
            exit 1
        fi
    done
done
