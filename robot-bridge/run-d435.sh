#!/usr/bin/env bash
set -eo pipefail
source /opt/ros/melodic/setup.bash
source /home/agilex/catkin_ws/devel/setup.bash
# Reuse the base driver's ROS master; stopping the camera must not stop that master.
exec /usr/bin/python2.7 /opt/ros/melodic/bin/roslaunch --wait --screen \
  realsense2_camera rs_camera.launch
