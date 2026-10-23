#!/usr/bin/env bash
# Start the MQTT bridge on the SCOUT MINI onboard computer.
#   SCOUT_WS=~/catkin_ws bash start_bridge.sh [extra scout_mqtt_bridge.py options]
# SCOUT_WS is the workspace that contains scout_msgs (catkin devel/ or colcon install/).
set -eo pipefail   # no -u: ROS setup scripts read unset variables

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -z "${ROS_DISTRO:-}" ]; then
  for setup in /opt/ros/humble/setup.bash /opt/ros/foxy/setup.bash /opt/ros/noetic/setup.bash; do
    if [ -f "$setup" ]; then
      # shellcheck disable=SC1090
      source "$setup"
      break
    fi
  done
fi
if [ -n "${SCOUT_WS:-}" ]; then
  for setup in "$SCOUT_WS/install/setup.bash" "$SCOUT_WS/devel/setup.bash"; do
    # shellcheck disable=SC1090
    if [ -f "$setup" ]; then source "$setup"; fi
  done
fi
if [ -z "${ROS_DISTRO:-}" ]; then
  echo "No ROS environment found. For a desk test without ROS use: $0 --ros sim" >&2
fi

# scout_base talks to the chassis over CAN; the bridge still runs without it, but reports chassis_connected=false.
if command -v ip >/dev/null && ! ip link show can0 2>/dev/null | grep -q "state UP"; then
  echo "warning: can0 is not up (sudo ip link set can0 up type can bitrate 500000)" >&2
fi

exec python3 "$HERE/scout_mqtt_bridge.py" --config "$HERE/config.yaml" "$@"
