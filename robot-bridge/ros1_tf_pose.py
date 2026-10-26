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
    parser.add_argument('--scan-topic')
    args = parser.parse_args()
    import rospy
    import tf2_ros
    from geometry_msgs.msg import PoseStamped
    rospy.init_node('scout_navigation_pose', anonymous=True)
    buffer = tf2_ros.Buffer()
    listener = tf2_ros.TransformListener(buffer)
    publisher = rospy.Publisher(args.topic, PoseStamped, queue_size=1)
    scan = [None]
    if args.scan_topic:
        from sensor_msgs.msg import LaserScan, PointCloud
        from geometry_msgs.msg import Point32
        rospy.Subscriber(args.scan_topic, LaserScan, lambda msg: scan.__setitem__(0, msg), queue_size=1)
        scan_pub = rospy.Publisher('/patrol/map_scan', PointCloud, queue_size=1)
    rate = rospy.Rate(5)
    while not rospy.is_shutdown():
        relay_once(buffer, publisher, rospy, PoseStamped, args.frame, args.base)
        if args.scan_topic and scan[0] is not None:
            try:
                msg = scan[0]
                if abs((rospy.Time.now()-msg.header.stamp).to_sec()) <= 1.0:
                    tf = buffer.lookup_transform(args.frame, msg.header.frame_id, msg.header.stamp, rospy.Duration(0.02))
                    scan_pub.publish(scan_cloud(msg, tf, PointCloud, Point32, args.frame))
            except Exception:
                pass  # Missing TF means no overlay, never synthetic laser points.
        rate.sleep()


def scan_cloud(scan, transform, cloud_type, point_type, frame):
    cloud = cloud_type()
    cloud.header.stamp, cloud.header.frame_id = scan.header.stamp, frame
    t, q = transform.transform.translation, transform.transform.rotation
    # First two columns of the full quaternion rotation, for planar scan rays.
    r00, r01 = 1-2*(q.y*q.y+q.z*q.z), 2*(q.x*q.y-q.z*q.w)
    r10, r11 = 2*(q.x*q.y+q.z*q.w), 1-2*(q.x*q.x+q.z*q.z)
    step = max(1, int(math.ceil(len(scan.ranges)/360.0)))
    for i in range(0, len(scan.ranges), step):
        distance = scan.ranges[i]
        if not math.isnan(distance) and not math.isinf(distance) and scan.range_min < distance < scan.range_max:
            angle = scan.angle_min+i*scan.angle_increment
            x, y = distance*math.cos(angle), distance*math.sin(angle)
            p = point_type()
            p.x, p.y = t.x+r00*x+r01*y, t.y+r10*x+r11*y
            cloud.points.append(p)
    return cloud


if __name__ == '__main__':
    main()
