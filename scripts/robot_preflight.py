#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Read-only Xavier inventory. Python 2.7/3 compatible; never sends a velocity/goal."""
from __future__ import print_function
import glob
import json
import os
import socket
import subprocess
try:
    from xmlrpc.client import ServerProxy
except ImportError:
    from xmlrpclib import ServerProxy


def main():
    socket.setdefaulttimeout(3)
    report = {"ros_distro": os.environ.get("ROS_DISTRO"),
              "video_devices": sorted(glob.glob('/dev/video*')),
              "packages": {}, "launch_files": [], "map_files": []}
    report['navigation_python_imports'] = {}
    interpreters = {
        'native_tf': ('/usr/bin/python2.7', 'import tf2_ros; from geometry_msgs.msg import PoseStamped; print("OK")'),
        'bridge_action': (os.path.expanduser('~/archiconda3/bin/python3'),
                          'import actionlib; from move_base_msgs.msg import MoveBaseAction; print("OK")')}
    for label, (interpreter, code) in interpreters.items():
        try:
            output = subprocess.check_output([interpreter, '-c', code], stderr=subprocess.STDOUT)
            report['navigation_python_imports'][label] = output.decode().strip()[-1000:]
        except (OSError, subprocess.CalledProcessError) as error:
            report['navigation_python_imports'][label] = getattr(error, 'output', b'NOT_AVAILABLE').decode('utf-8', 'replace')[-1000:]
    for package in ('scout_bringup', 'scout_base', 'move_base', 'navfn', 'base_local_planner', 'amcl', 'map_server',
                    'realsense2_camera', 'usb_cam', 'rslidar_pointcloud', 'pointcloud_to_laserscan'):
        try:
            path = subprocess.check_output(['rospack', 'find', package], stderr=subprocess.STDOUT).decode().strip()
            report['packages'][package] = path
            if package in ('scout_bringup', 'scout_base'):
                report['launch_files'] += sorted(glob.glob(os.path.join(path, 'launch', '*.launch')))
        except (OSError, subprocess.CalledProcessError):
            report['packages'][package] = None
    for root in (os.path.expanduser('~/catkin_ws/src'), os.path.expanduser('~/maps')):
        if not os.path.isdir(root):
            continue
        for directory, subdirs, files in os.walk(root):
            subdirs[:] = [d for d in subdirs if d not in ('.git', 'node_modules', 'build')]
            if 'map' in directory.lower():
                report['map_files'] += [os.path.join(directory, f) for f in files
                                        if f.endswith(('.yaml', '.pgm'))][:40]
            if len(report['map_files']) >= 80:
                break
    try:
        master = ServerProxy(os.environ.get('ROS_MASTER_URI', 'http://localhost:11311'))
        report['topics'] = master.getTopicTypes('/patrol_preflight')[2]
        report['nodes'] = sorted(set(n for group in master.getSystemState('/patrol_preflight')[2]
                                    for _, nodes in group for n in nodes))
    except Exception as error:
        report['ros_master_error'] = type(error).__name__
    try:
        report['camera_inventory'] = subprocess.check_output(
            ['v4l2-ctl', '--list-devices'], stderr=subprocess.STDOUT).decode()[:3000]
    except (OSError, subprocess.CalledProcessError):
        report['camera_inventory'] = 'v4l2-ctl unavailable; no package installed by this script'
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
