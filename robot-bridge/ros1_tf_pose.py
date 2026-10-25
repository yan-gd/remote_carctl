#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Native ROS interpreter reads TF and relays only its measured map pose.

Melodic's tf2_py is a native Python2 extension. The Python3 MQTT process reads
the standard PoseStamped message instead of importing that incompatible binary.
This process never publishes velocity, odometry, goals or synthetic poses.
"""
from __future__ import print_function
import argparse
import math


def relay_once(buffer, publisher, rospy, pose_type, frame, base):
    try:
        transform = buffer.lookup_transform(frame, base, rospy.Time(0))
        if abs((rospy.Time.now() - transform.header.stamp).to_sec()) > 1.5:
            return False
        t, q = transform.transform.translation, transform.transform.rotation
        values = (t.x, t.y, t.z, q.x, q.y, q.z, q.w)
        if any(math.isnan(v) or math.isinf(v) for v in values):
            return False
        if not 0.95 <= sum(v*v for v in values[3:]) <= 1.05:
            return False
        pose = pose_type()
        pose.header = transform.header
        pose.pose.position.x, pose.pose.position.y, pose.pose.position.z = t.x, t.y, t.z
        pose.pose.orientation = q
        publisher.publish(pose)
        return True
    except Exception as error:
        rospy.logwarn_throttle(10, 'Navigation TF unavailable (%s)', type(error).__name__)
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--frame', required=True)
    parser.add_argument('--base', required=True)
    parser.add_argument('--topic', required=True)
    args = parser.parse_args()
    import rospy
    import tf2_ros
    from geometry_msgs.msg import PoseStamped
    rospy.init_node('scout_navigation_pose', anonymous=True)
    buffer = tf2_ros.Buffer()
    listener = tf2_ros.TransformListener(buffer)
    publisher = rospy.Publisher(args.topic, PoseStamped, queue_size=1)
    rate = rospy.Rate(5)
    while not rospy.is_shutdown():
        relay_once(buffer, publisher, rospy, PoseStamped, args.frame, args.base)
        rate.sleep()


if __name__ == '__main__':
    main()
