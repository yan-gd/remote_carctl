#!/usr/bin/env bash
set -eo pipefail
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
set -u
cd /home/agilex/Desktop/remote_carctl/robot-bridge
exec "${CAMERA_PYTHON:-/home/agilex/archiconda3/bin/python3}" scout_camera_uplink.py \
  --server "${CAMERA_SERVER:?CAMERA_SERVER is required}" \
  --ros-topic "${CAMERA_ROS_TOPIC:-/camera/color/image_raw/compressed}" \
  --fps "${CAMERA_FPS:-4}"
