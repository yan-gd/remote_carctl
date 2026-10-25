import unittest
from types import SimpleNamespace as NS
from unittest.mock import Mock
from ros1_tf_pose import relay_once


class Stamp:
    def __init__(self,value):self.value=value
    @staticmethod
    def now():return Stamp(10)
    def __sub__(self,other):return NS(to_sec=lambda:self.value-other.value)


class PoseRelayTest(unittest.TestCase):
    def setUp(self):
        self.transform=NS(header=NS(stamp=Stamp(10),frame_id='map'),transform=NS(
            translation=NS(x=1,y=2,z=0),rotation=NS(x=0,y=0,z=0,w=1)))
        self.buffer=NS(lookup_transform=lambda *args:self.transform)
        self.publisher=Mock()
        self.rospy=NS(Time=Stamp,logwarn_throttle=Mock())
        self.pose_type=lambda:NS(pose=NS(position=NS(x=0,y=0,z=0),orientation=None))

    def relay(self):return relay_once(self.buffer,self.publisher,self.rospy,self.pose_type,'map','base_link')

    def test_only_measured_fresh_tf_is_published_with_its_original_timestamp(self):
        self.assertTrue(self.relay())
        pose=self.publisher.publish.call_args[0][0]
        self.assertEqual((1,2,0),(pose.pose.position.x,pose.pose.position.y,pose.pose.position.z))
        self.assertIs(self.transform.header,pose.header)
        self.assertEqual(1,pose.pose.orientation.w)

    def test_missing_stale_nonfinite_or_invalid_rotation_never_generates_a_pose(self):
        self.transform.header.stamp=Stamp(5)
        self.assertFalse(self.relay())
        self.transform.header.stamp=Stamp(10)
        self.transform.transform.translation.x=float('nan')
        self.assertFalse(self.relay())
        self.transform.transform.translation.x=1;self.transform.transform.rotation.w=0
        self.assertFalse(self.relay())
        self.buffer.lookup_transform=Mock(side_effect=RuntimeError('no transform'))
        self.assertFalse(self.relay())
        self.publisher.publish.assert_not_called()
