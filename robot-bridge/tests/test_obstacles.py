import math
import time
import unittest
from types import SimpleNamespace as S
from scout_obstacles import forward_obstacle
from scout_navigation import Ros1NavigationAdapter, NavigationManager
from scout_bridge_core import load_config
from test_pose_relay import Stamp
from unittest.mock import Mock


class ObstacleTests(unittest.TestCase):
    def test_corridor_uses_rotated_robot_frame_and_requires_two_returns(self):
        self.assertAlmostEqual(.4, forward_obstacle([S(x=1,y=2.4),S(x=1.1,y=2.5)],1,2,math.pi/2))
        for points in ([S(x=.5,y=1),S(x=.6,y=1)], [S(x=-.5,y=0),S(x=-.6,y=0)],
                       [S(x=2,y=0),S(x=2.1,y=0)], [S(x=.5,y=0)], [S(x=math.nan,y=0)]):
            self.assertIsNone(forward_obstacle(points,0,0,0))

    def adapter(self):
        a=object.__new__(Ros1NavigationAdapter)
        a.config={'frame':'map'};a.rospy=S(Time=Stamp)
        a.pose=S(header=S(frame_id='map',stamp=Stamp(10)),pose=S(position=S(x=0,y=0),orientation=S(x=0,y=0,z=0,w=1)))
        a.pose_at=time.monotonic()
        cloud=S(header=S(frame_id='map',stamp=Stamp(10)),points=[S(x=.5,y=0),S(x=.6,y=.1)])
        a.obstacle_cloud=(cloud,time.monotonic())
        return a

    def test_unknown_when_pose_cloud_frames_or_timestamps_are_invalid(self):
        a=self.adapter();self.assertTrue(a.obstacle_report()['obstacle_detected'])
        a.obstacle_cloud[0].header.frame_id='laser'
        self.assertIsNone(a.obstacle_report()['obstacle_detected'])
        a=self.adapter();a.pose.header.stamp=Stamp(9)
        self.assertIsNone(a.obstacle_report()['obstacle_detected'])
        a=self.adapter();a.obstacle_cloud=(a.obstacle_cloud[0],time.monotonic()-2)
        self.assertIsNone(a.obstacle_report()['obstacle_detected'])
        a=self.adapter();a.pose_at-=2
        self.assertIsNone(a.obstacle_report()['obstacle_detected'])
        for points in ([], [S(x=math.nan,y=0)]):
            a=self.adapter();a.obstacle_cloud[0].points=points
            self.assertIsNone(a.obstacle_report()['obstacle_detected'])

    def test_reports_evidence_only_in_active_navigation_and_never_changes_velocity(self):
        a=self.adapter();a.health=lambda:{};send=Mock()
        nav=NavigationManager(load_config()['navigation'],a,send)
        self.assertNotIn('obstacle_detected',nav.report())
        nav.state='ACTIVE'
        self.assertEqual(.5,nav.report()['obstacle_distance'])
        send.assert_not_called()
        nav.state='CANCELLED';self.assertNotIn('obstacle_detected',nav.report())
        nav.state='ACTIVE';nav.config=dict(nav.config,direct=True)
        self.assertNotIn('obstacle_detected',nav.report())
