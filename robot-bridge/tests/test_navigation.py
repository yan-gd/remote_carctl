import copy
import json
import math
import unittest
import time
import os
import xml.etree.ElementTree as ET
from types import SimpleNamespace as NS
from test_core import BridgeTestCase, FakeClock, control
from scout_bridge_core import load_config, ConfigError
from scout_navigation import NavigationManager, Ros1NavigationAdapter


class Adapter:
    def __init__(self):
        self.health_value = dict(ready=True, map_id='map-a', frame='map', reason='READY')
        self.goals = []
        self.cancelled = 0
        self.allowed = True
        self.fail = False
        self.odometry = (0., 0., 0.)

    def health(self): return dict(self.health_value)
    def motion_pose(self): return self.odometry
    def goal_allowed(self, point): return self.allowed
    def send_goal(self, point, done):
        if self.fail: raise RuntimeError('action server down')
        self.goals.append((point, done))
    def cancel(self): self.cancelled += 1


class NavigationTest(BridgeTestCase):
    def setUp(self):
        super().setUp()
        self.adapter = Adapter()
        self.nav = NavigationManager(self.config['navigation'], self.adapter, self.ros.publish_velocity,
                                     self.clock, self.clock)
        self.bridge.navigation = self.nav

    def order(self, action='START', **overrides):
        order = dict(action=action, missionId='mission-1', mapId='map-a',
                     points=[dict(x=1, y=2, yaw=0)], cycles=1)
        order.update(overrides)
        return dict(expires_at=self.clock()*1000+3000, order=order)

    def test_single_goal_only_succeeds_on_real_action_result(self):
        self.nav.command(self.order())
        self.assertEqual('ACTIVE', self.nav.report()['state'])
        self.assertEqual(1, len(self.adapter.goals))
        self.nav.accept_velocity(5, -5)
        self.nav.tick()
        self.assertEqual((0.2, -0.4), self.ros.velocities[-1])
        self.clock.advance(.4)
        self.nav.tick()
        self.assertEqual((0, 0), self.ros.velocities[-1])
        self.adapter.goals[0][1](3)
        self.assertEqual('SUCCEEDED', self.nav.state)
        self.assertEqual(1, self.nav.index)
        self.assertFalse(self.nav.tick())

    def test_waypoints_repeat_and_failed_result_aborts_instead_of_skipping(self):
        self.nav.command(self.order(points=[dict(x=1,y=2,yaw=0),dict(x=3,y=4,yaw=1)],cycles=2))
        self.assertEqual(4, self.nav.report()['total_points'])
        self.adapter.goals[0][1](3)
        self.assertEqual(2, len(self.adapter.goals))
        self.assertEqual(3, self.adapter.goals[1][0]['x'])
        self.adapter.goals[1][1](4)
        self.assertEqual('ABORTED', self.nav.state)
        self.assertEqual(2, len(self.adapter.goals))

    def test_lease_timeout_stops_and_late_keepalive_cannot_revive(self):
        self.nav.command(self.order())
        self.clock.advance(2)
        self.nav.command(self.order('KEEPALIVE', missionId='someone-else'))
        self.clock.advance(1.1)
        self.nav.command(self.order('KEEPALIVE'))
        self.nav.tick()
        self.assertEqual('CANCELLED', self.nav.state)
        self.assertEqual('LEASE_EXPIRED', self.nav.reason)
        self.assertEqual((0,0), self.ros.velocities[-1])
        self.assertEqual(1,self.adapter.cancelled)

    def test_matching_keepalive_preserves_lease_but_goal_timeout_remains(self):
        self.nav.config = dict(self.config['navigation'],goal_timeout=10)
        self.nav.command(self.order())
        for _ in range(6):
            self.clock.advance(2)
            self.nav.command(self.order('KEEPALIVE'))
            self.nav.tick()
        self.assertEqual('GOAL_TIMEOUT',self.nav.reason)

    def step(self, seconds=1):
        self.clock.advance(seconds)
        self.nav.command(self.order('KEEPALIVE'))
        self.nav.accept_velocity(0., 0.3)
        self.nav.tick()

    def test_in_place_full_turn_stops_even_with_fresh_leases_and_wrapped_yaw(self):
        self.nav.command(self.order(cycles=2)); self.nav.tick()
        done = self.adapter.goals[0][1]
        for i in range(1, 18):
            yaw = 0.4*i
            self.adapter.odometry = (0., 0., math.atan2(math.sin(yaw), math.cos(yaw)))
            self.step()
        self.assertEqual('NAVIGATION_SPIN_LIMIT', self.nav.reason)
        self.assertEqual('CANCELLED', self.nav.state)
        self.assertEqual((0., 0.), self.ros.velocities[-1])
        self.assertEqual(1, self.adapter.cancelled)
        done(3)  # Late completion cannot advance a cancelled patrol.
        self.assertEqual(1, len(self.adapter.goals))

    def test_no_displacement_times_out_despite_localization_jitter_and_renewals(self):
        self.nav.command(self.order()); self.nav.tick()
        for i in range(30):
            self.adapter.odometry = (0.01*(i%2), 0., 0.)
            self.adapter.health_value.update(x=i, y=i)  # AMCL jumps are not motion.
            self.step()
        self.assertEqual('NAVIGATION_NO_PROGRESS', self.nav.reason)
        self.assertEqual((0., 0.), self.ros.velocities[-1])

    def test_normal_half_turn_and_measured_translation_reset_progress_guard(self):
        self.nav.command(self.order()); self.nav.tick()
        for i in range(1, 22):
            self.adapter.odometry = (0., 0., i*0.15)
            self.step()
        self.assertEqual('ACTIVE', self.nav.state)
        for i in range(1, 41):
            self.adapter.odometry = (i*0.02, 0., math.pi)
            self.step()
        self.assertEqual('ACTIVE', self.nav.state)
        self.adapter.goals[0][1](3)
        self.assertEqual('SUCCEEDED', self.nav.state)

    def test_each_waypoint_gets_a_new_progress_window(self):
        self.nav.command(self.order(cycles=2)); self.nav.tick()
        for _ in range(25): self.step()
        self.adapter.goals[0][1](3)
        for _ in range(10): self.step()
        self.assertEqual('ACTIVE', self.nav.state)
        self.assertEqual(1, self.nav.index)

    def test_invalid_measured_odometry_stops_without_publishing_motion(self):
        for sample in ((None, 0, 0), (0, float('nan'), 0)):
            self.nav.command(self.order())
            self.adapter.odometry = sample
            self.step()
            self.assertEqual('NAVIGATION_ODOMETRY_UNAVAILABLE', self.nav.reason)
            self.assertEqual((0., 0.), self.ros.velocities[-1])

    def test_health_loss_map_change_invalid_velocity_and_cancel_fail_closed(self):
        for cause in ('health','map','velocity','cancel'):
            self.adapter.health_value.update(ready=True,map_id='map-a')
            self.nav.command(self.order())
            if cause=='health': self.adapter.health_value['ready']=False
            elif cause=='map': self.adapter.health_value['map_id']='changed'
            elif cause=='velocity': self.nav.accept_velocity(math.nan,0)
            else: self.nav.command(self.order('CANCEL'))
            self.nav.tick()
            self.assertEqual('CANCELLED',self.nav.state,cause)
            self.assertEqual((0,0),self.ros.velocities[-1])

    def test_rejects_bad_map_occupied_goal_and_nonfinite_input(self):
        for overrides in (dict(mapId='old'),dict(points=[dict(x=math.nan,y=0,yaw=0)]),
                          dict(cycles=True),dict(points=[]),dict(points=[dict(x=0,y=0,yaw=4)])):
            self.nav.command(self.order(**overrides))
            self.assertEqual('REJECTED',self.nav.state)
            self.assertEqual('mission-1',self.nav.report()['mission_id'])
        self.adapter.allowed=False
        self.nav.command(self.order())
        self.assertEqual('GOAL_NOT_IN_FREE_MAP_SPACE',self.nav.reason)
        self.assertEqual([],self.adapter.goals)

    def test_expired_start_and_failed_goal_delivery_never_move(self):
        message=self.order(); message['expires_at']=self.clock()*1000-1
        self.nav.command(message)
        self.assertEqual('IDLE',self.nav.state)
        self.adapter.fail=True
        self.nav.command(self.order())
        self.assertEqual('GOAL_DELIVERY_FAILED',self.nav.reason)
        self.assertEqual((0,0),self.ros.velocities[-1])

    def test_manual_stop_wins_and_late_action_result_cannot_start_next_point(self):
        self.nav.command(self.order(cycles=2))
        done=self.adapter.goals[0][1]
        self.bridge.on_control(control('STOP',seq=10,session='backend'))
        done(3)
        self.assertEqual('CANCELLED',self.nav.state)
        self.assertEqual(1,len(self.adapter.goals))
        self.assertEqual((0,0),self.ros.velocities[-1])

    def test_shutdown_stops_manual_motion_before_helper_cleanup_and_ignores_late_commands(self):
        self.bridge.on_control(control('FORWARD',seq=1,session='backend'))
        self.assertGreater(self.ros.velocities[-1][0],0)
        def cleanup():
            self.assertEqual((0,0),self.ros.velocities[-1])
            self.bridge.on_control(control('FORWARD',seq=2,session='backend'))
            self.assertEqual((0,0),self.ros.velocities[-1])
        self.adapter.close=cleanup
        self.bridge.shutdown()
        self.assertEqual('STOPPED',self.bridge.motion.state)
        self.bridge.on_connected()
        self.assertFalse(self.link.messages('heartbeat')[-1]['online'])

    def test_navigation_sequence_is_shared_with_manual_stop_and_retained_start_is_ignored(self):
        message=dict(self.order(),robot_id='SCOUT-001',session='backend',seq=1)
        self.bridge.on_navigation(json.dumps(message).encode(),retained=True)
        self.assertEqual('IDLE',self.nav.state)
        self.bridge.on_navigation(json.dumps(message).encode())
        self.assertEqual('ACTIVE',self.nav.state)
        self.bridge.on_control(control('STOP',seq=2,session='backend'))
        self.bridge.on_navigation(json.dumps(message).encode())
        self.assertEqual('CANCELLED',self.nav.state)
        self.nav.command(self.order())
        self.bridge.on_disconnected()
        self.assertEqual('MQTT_DISCONNECTED',self.nav.reason)


class GridTest(unittest.TestCase):
    def test_progress_pose_uses_only_fresh_odometry(self):
        from scout_bridge_core import TelemetryCache
        clock = FakeClock()
        adapter = object.__new__(Ros1NavigationAdapter)
        adapter.telemetry = TelemetryCache(1, clock)
        adapter.telemetry.update_odometry(1, 2, 0.3)
        self.assertEqual((1, 2, 0.3), adapter.motion_pose())
        clock.advance(2)
        self.assertEqual((None, None, None), adapter.motion_pose())

    def health_adapter(self):
        class Stamp:
            def __init__(self,value=0): self.value=value
            @staticmethod
            def now(): return Stamp(10)
            def __sub__(self,other): return NS(to_sec=lambda:self.value-other.value)
        adapter=object.__new__(Ros1NavigationAdapter)
        adapter.config=load_config()['navigation']
        adapter.map=NS(header=NS(frame_id='map'));adapter.map_id='map-a';adapter.confirmed_map='map-a'
        adapter.scan_at=adapter.costmap_at=time.monotonic()
        adapter.telemetry=NS(chassis_connected=lambda:True)
        adapter.client=NS(wait_for_server=lambda _:True)
        adapter.pose=NS(header=NS(stamp=Stamp(10)),pose=NS(position=NS(x=1,y=2),orientation=NS(x=0,y=0,z=0,w=1)))
        adapter.pose_at=time.monotonic();adapter.pose_process=NS(poll=lambda:None)
        adapter.ros=NS(_publisher=NS(resolved_name='/cmd_vel'))
        adapter.publishers=['/bridge']
        adapter.rospy=NS(Time=Stamp,Duration=lambda x:x,get_name=lambda:'/bridge',
            get_master=lambda:NS(getSystemState=lambda:(1,'',[[['/cmd_vel',adapter.publishers]],[],[]])))
        return adapter

    def test_ros_readiness_requires_localization_fresh_sensors_and_exclusive_velocity(self):
        adapter=self.health_adapter()
        self.assertTrue(adapter._check_health()['ready'])
        adapter.publishers.append('/unsafe_direct_navigation')
        report=adapter._check_health()
        self.assertFalse(report['ready']);self.assertEqual('DIRECT_CMD_VEL_PUBLISHER',report['reason'])
        adapter.publishers=['/bridge'];adapter.scan_at-=5
        self.assertEqual('SENSOR_OR_COSTMAP_STALE',adapter._check_health()['reason'])
        adapter.scan_at=time.monotonic();adapter.client.wait_for_server=lambda _:False
        self.assertEqual('MOVE_BASE_UNAVAILABLE',adapter._check_health()['reason'])

    def test_health_read_is_nonblocking_and_expires_background_check_results(self):
        adapter=self.health_adapter()
        adapter.health_cache=adapter._check_health();adapter.health_at=time.monotonic()
        self.assertTrue(adapter.health()['ready'])
        adapter.health_at-=1
        self.assertFalse(adapter.health()['ready'])
        self.assertEqual('NAVIGATION_CHECK_STALE',adapter.health()['reason'])

    def test_a_fresh_operator_initial_pose_is_required_for_the_current_map(self):
        adapter=self.health_adapter();adapter.confirmed_map=None
        self.assertEqual('INITIAL_POSE_REQUIRED',adapter._check_health()['reason'])
        adapter._initialpose(NS(header=NS(frame_id='map',stamp=adapter.rospy.Time(10))))
        self.assertTrue(adapter._check_health()['ready'])
        adapter.map_id='different-map'
        self.assertEqual('INITIAL_POSE_REQUIRED',adapter._check_health()['reason'])

    def test_native_pose_relay_failure_or_staleness_blocks_navigation(self):
        adapter=self.health_adapter()
        adapter.pose_process=NS(poll=lambda:1)
        self.assertEqual('LOCALIZATION_UNAVAILABLE',adapter._check_health()['reason'])
        adapter.pose_process=NS(poll=lambda:None);adapter.pose_at-=1
        self.assertEqual('LOCALIZATION_UNAVAILABLE',adapter._check_health()['reason'])

    def test_goal_must_have_clearance_and_be_inside_known_free_map(self):
        adapter=object.__new__(Ros1NavigationAdapter)
        adapter.map=NS(info=NS(width=20,height=20,resolution=.1,
            origin=NS(position=NS(x=0,y=0),orientation=NS(z=0,w=1))),data=[0]*400)
        self.assertTrue(adapter.goal_allowed(dict(x=1,y=1)))
        self.assertFalse(adapter.goal_allowed(dict(x=0,y=0)))
        adapter.map.data[10*20+10]=-1
        self.assertFalse(adapter.goal_allowed(dict(x=1,y=1)))
        adapter.map.data[10*20+10]=100
        self.assertFalse(adapter.goal_allowed(dict(x=1,y=1)))

    def test_navigation_is_opt_in_and_cannot_share_chassis_velocity_topic(self):
        self.assertFalse(load_config()['navigation']['enabled'])
        with self.assertRaises(ConfigError):
            load_config({'navigation':{'velocity_topic':'/cmd_vel'}})
        with self.assertRaises(ConfigError):
            load_config({'mqtt':{'password':123456}})


class LaunchGuardTest(unittest.TestCase):
    def launch(self,name):
        return ET.parse(os.path.join(os.path.dirname(os.path.dirname(__file__)),'launch',name)).getroot()

    def test_cloud_navigation_requires_selected_map_and_cannot_bypass_velocity_gate(self):
        root=self.launch('cloud_navigation.launch')
        selection=root.find("arg[@name='map_yaml']")
        self.assertIsNotNone(selection)
        self.assertNotIn('default',selection.attrib)
        self.assertEqual({'move_base','amcl','map_server'},{n.get('pkg') for n in root.findall('node')})
        self.assertEqual([],root.findall('include'))
        node=root.find("node[@name='move_base']")
        self.assertEqual('/navigation/cmd_vel',node.find("remap[@from='cmd_vel']").get('to'))
        for side in ('global_costmap','local_costmap'):
            self.assertEqual('base_link',node.find("param[@name='%s/robot_base_frame']"%side).get('value'))
            self.assertIn('costmap_2d::ObstacleLayer',node.find("rosparam[@param='%s/plugins']"%side).text)

    def test_cloud_lidar_does_not_start_another_odometry_or_base_driver(self):
        root=self.launch('cloud_sensors.launch')
        self.assertEqual({'tf'},{n.get('pkg') for n in root.findall('node')})
        includes=[n.get('file') for n in root.findall('include')]
        self.assertEqual(2,len(includes))
        self.assertTrue(any('rslidar_sdk' in p for p in includes))
        self.assertTrue(any('pointcloud_to_laserscan' in p for p in includes))

    def test_map_planner_owns_matching_low_speed_limits_and_disables_spin_recovery(self):
        import yaml
        node = self.launch('cloud_navigation.launch').find("node[@name='move_base']")
        files = [p.get('file', '') for p in node.findall('rosparam')]
        self.assertIn('$(dirname)/map_navigation.yaml', files)
        self.assertFalse(any('move_base_params.yaml' in f or 'base_local_planner_params.yaml' in f for f in files))
        path = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'launch', 'map_navigation.yaml')
        with open(path) as source: config = yaml.safe_load(source)
        self.assertEqual('base_local_planner/TrajectoryPlannerROS', config['base_local_planner'])
        planner = config['TrajectoryPlannerROS']
        self.assertFalse(planner['holonomic_robot'])
        limits = load_config()['navigation']
        self.assertLessEqual(planner['max_vel_x'], limits['max_linear'])
        self.assertLessEqual(planner['max_rotational_vel'], limits['max_angular'])
        self.assertEqual(0, planner['escape_vel'])
        self.assertFalse(config['recovery_behavior_enabled'])
        self.assertFalse(config['clearing_rotation_allowed'])
        self.assertEqual(30, config['oscillation_timeout'])
        self.assertFalse(config['NavfnROS']['allow_unknown'])
