import math
import os
import time
import unittest
import uuid
import xml.etree.ElementTree as ET
from types import SimpleNamespace as NS
from unittest.mock import patch

import test_navigation as fixtures
from scout_bridge_core import load_config, ConfigError
from scout_navigation import RelativeRos1NavigationAdapter, relative_goal


class RelativeGeometryTest(unittest.TestCase):
    def test_offset_rotates_with_initial_heading_and_is_not_mutated(self):
        point = dict(x=2, y=1, yaw=math.pi)
        goal = relative_goal(point, 10, 20, math.pi/2)
        self.assertAlmostEqual(9, goal['x'])
        self.assertAlmostEqual(22, goal['y'])
        self.assertAlmostEqual(-math.pi/2, goal['yaw'])
        self.assertEqual(dict(x=2, y=1, yaw=math.pi), point)

    def test_radius_is_euclidean_not_separate_axis_bounds(self):
        for x, y in ((0, 0), (0.01, 0), (3, 3), (3.01, 0)):
            with self.subTest(x=x, y=y), self.assertRaises(ValueError):
                relative_goal(dict(x=x, y=y, yaw=0), 0, 0, 0)
        self.assertEqual(3, relative_goal(dict(x=3, y=0, yaw=0), 0, 0, 0)['x'])

    def test_relative_config_forces_odom_and_caps_speed_and_timeout(self):
        nav = load_config({'navigation': {'mode': 'relative'}})['navigation']
        self.assertEqual('odom', nav['frame'])
        self.assertEqual('/move_base/global_costmap/costmap', nav['map_topic'])
        self.assertEqual((0.15, 0.3, 60), (nav['max_linear'], nav['max_angular'], nav['goal_timeout']))
        self.assertFalse(nav['enabled'])
        with self.assertRaises(ConfigError):
            load_config({'navigation': {'mode': 'anything'}})

    def test_cli_enables_relative_without_rewriting_mqtt_credentials(self):
        from scout_mqtt_bridge import parse_args, build_config
        with patch('scout_mqtt_bridge.read_config_file', return_value={
                'mqtt': {'host': '192.0.2.10', 'username': 'robot', 'password': 'test-only'}}):
            config = build_config(parse_args(['--ros', '1', '--navigation-mode', 'relative']))
        self.assertTrue(config['navigation']['enabled'])
        self.assertEqual('relative', config['navigation']['mode'])
        self.assertEqual('192.0.2.10', config['mqtt']['host'])
        self.assertEqual('test-only', config['mqtt']['password'])


class RelativeManagerTest(unittest.TestCase):
    def setUp(self):
        # Reuse test fixtures, not inherited tests (which must stay in map mode).
        fixture = fixtures.NavigationTest()
        fixture.setUp()
        self.fixture, self.nav, self.adapter = fixture, fixture.nav, fixture.adapter
        self.nav.config = load_config({'navigation': {'mode': 'relative'}})['navigation']
        self.adapter.health_value.update(x=10, y=20, yaw=math.pi/2, frame='odom')
        self.adapter.prepare_points = lambda points: [relative_goal(p, 10, 20, math.pi/2) for p in points]

    def start(self, **kw):
        self.nav.command(self.fixture.order(mode='relative', **kw))

    def test_goal_is_frozen_and_live_obstacle_changes_do_not_move_it(self):
        self.start(points=[dict(x=2, y=1, yaw=0)])
        self.assertEqual('ACTIVE', self.nav.state)
        goal = self.adapter.goals[0][0].copy()
        self.assertAlmostEqual(9, goal['x'])
        self.adapter.health_value.update(x=10.1, y=20.2)
        self.nav.accept_velocity(10, -10)
        self.nav.tick()
        self.assertEqual((0.15, -0.3), self.fixture.ros.velocities[-1])
        self.assertEqual(goal, self.adapter.goals[0][0])
        self.assertEqual(1, len(self.adapter.goals))
        self.assertEqual('relative', self.nav.report()['mode'])
        self.adapter.goals[0][1](3)
        self.assertEqual('SUCCEEDED', self.nav.state)

    def test_rejects_repeat_multiple_points_wrong_mode_unknown_space_and_excess_range(self):
        cases = [dict(cycles=2), dict(points=[dict(x=1,y=0,yaw=0)]*2),
                 dict(mode='map'), dict(points=[dict(x=3,y=3,yaw=0)])]
        for case in cases:
            order = self.fixture.order(mode='relative')
            order['order'].update(case)
            self.nav.command(order)
            self.assertEqual('REJECTED', self.nav.state)
        self.adapter.allowed = False
        self.start()
        self.assertEqual('GOAL_NOT_IN_FREE_MAP_SPACE', self.nav.reason)
        self.assertEqual([], self.adapter.goals)

    def test_stale_reference_and_sensor_loss_cancel_and_do_not_resume(self):
        self.start()
        self.adapter.health_value['map_id'] = 'reset-odom'
        self.nav.tick()
        self.assertEqual('CANCELLED', self.nav.state)
        self.assertEqual((0,0), self.fixture.ros.velocities[-1])
        self.adapter.health_value['map_id'] = 'map-a'
        self.start()
        self.adapter.health_value['ready'] = False
        self.nav.tick()
        self.assertEqual('CANCELLED', self.nav.state)
        self.assertEqual('NAVIGATION_HEALTH_LOST', self.nav.reason)

    def test_lease_and_goal_timeout_and_detour_bounds_stop_real_velocity(self):
        self.start()
        self.fixture.clock.advance(3.1)
        self.nav.tick()
        self.assertEqual('LEASE_EXPIRED', self.nav.reason)
        self.start()
        self.nav.goal_at -= 61
        self.nav.tick()
        self.assertEqual('GOAL_TIMEOUT', self.nav.reason)
        self.start()
        self.adapter.health_value.update(x=14.1)
        self.nav.tick()
        self.assertEqual('RELATIVE_TRAVEL_LIMIT', self.nav.reason)
        self.assertEqual((0,0), self.fixture.ros.velocities[-1])

    def test_manual_stop_overrides_relative_motion_and_blocks_late_completion(self):
        self.start()
        done = self.adapter.goals[0][1]
        self.nav.accept_velocity(0.15, 0)
        self.nav.tick()
        from test_core import control
        self.fixture.bridge.on_control(control('STOP', seq=100, session='backend'))
        done(3)
        self.assertEqual('CANCELLED', self.nav.state)
        self.assertEqual((0,0), self.fixture.ros.velocities[-1])

    def test_cumulative_detour_is_bounded_even_when_robot_returns_near_start(self):
        self.start()
        for x, y in ((13, 20), (10, 20), (10, 20.2)):
            self.adapter.health_value.update(x=x,y=y)
            self.nav.tick()
        self.assertEqual('CANCELLED', self.nav.state)
        self.assertEqual('RELATIVE_TRAVEL_LIMIT', self.nav.reason)
        self.assertEqual((0,0), self.fixture.ros.velocities[-1])


class RelativeAdapterTest(unittest.TestCase):
    def adapter(self):
        source = fixtures.GridTest().health_adapter()
        result = object.__new__(RelativeRos1NavigationAdapter)
        result.__dict__.update(source.__dict__)
        result.config = load_config({'navigation': {'mode': 'relative'}})['navigation']
        result.reference_id = 'relative-' + uuid.uuid4().hex
        result.map.header.frame_id = 'odom'
        result.map_at = time.monotonic()
        result.confirmed_map = None
        result.telemetry.report = lambda: dict(linear_velocity=0, angular_velocity=0)
        return result

    def grid(self, adapter):
        return NS(header=NS(frame_id='odom', stamp=adapter.rospy.Time(10)),
                  info=NS(width=120,height=120,resolution=.1,
                          origin=NS(position=NS(x=-6,y=-6),orientation=NS(z=0,w=1))), data=[0]*14400)

    def test_changing_obstacles_and_rolling_origin_keep_reference_but_stale_grid_fails(self):
        adapter = self.adapter()
        grid = self.grid(adapter)
        adapter._map(grid)
        reference = adapter.map_id
        grid.data[0] = 100
        grid.info.origin.position.x += 0.1
        adapter._map(grid)
        self.assertEqual(reference, adapter.map_id)
        self.assertTrue(adapter._check_health()['ready'])
        adapter.map_at -= 3
        self.assertEqual('LIVE_GRID_UNAVAILABLE', adapter._check_health()['reason'])

    def test_target_in_unknown_or_occupied_grid_is_rejected(self):
        adapter = self.adapter()
        adapter._map(self.grid(adapter))
        self.assertTrue(adapter.goal_allowed(dict(x=0,y=0)))
        adapter.map.data[60*120+60] = -1
        self.assertFalse(adapter.goal_allowed(dict(x=0,y=0)))
        adapter.map.data[60*120+60] = 100
        self.assertFalse(adapter.goal_allowed(dict(x=0,y=0)))

    def test_odom_jump_invalidates_reference_and_needs_fresh_grid(self):
        adapter = self.adapter()
        adapter._map(self.grid(adapter))
        reference = adapter.map_id
        adapter._pose(NS(header=NS(frame_id='odom'),pose=NS(position=NS(x=0,y=0),orientation=NS(x=0,y=0,z=0,w=1))))
        self.assertNotEqual(reference, adapter.map_id)
        self.assertIsNone(adapter.map)
        self.assertFalse(adapter._check_health()['ready'])

    def test_preparation_uses_cached_pose_and_rejects_motion_and_expired_health(self):
        adapter = self.adapter()
        adapter._map(self.grid(adapter))
        adapter.health_cache = adapter._check_health()
        adapter.health_at = time.monotonic()
        with patch.object(adapter, '_check_health', side_effect=AssertionError('Blocking RPC on MQTT thread')):
            goal = adapter.prepare_points([dict(x=1,y=0,yaw=0)])[0]
            self.assertAlmostEqual(2, goal['x'])
            adapter.telemetry.report = lambda: dict(linear_velocity=.2,angular_velocity=0)
            with self.assertRaisesRegex(ValueError,'ROBOT_MUST_BE_STOPPED'):
                adapter.prepare_points([dict(x=1,y=0,yaw=0)])
            adapter.health_at -= 1
            with self.assertRaisesRegex(ValueError,'NAVIGATION_NOT_READY'):
                adapter.prepare_points([dict(x=1,y=0,yaw=0)])

    def test_launch_uses_live_odom_grid_and_the_same_velocity_gate(self):
        folder = os.path.join(os.path.dirname(os.path.dirname(__file__)), 'launch')
        root = ET.parse(os.path.join(folder,'cloud_relative_navigation.launch')).getroot()
        self.assertEqual(['move_base'], [n.get('pkg') for n in root.findall('node')])
        node = root.find("node[@name='move_base']")
        self.assertEqual('/navigation/cmd_vel', node.find("remap[@from='cmd_vel']").get('to'))
        import yaml
        with open(os.path.join(folder,'relative_navigation.yaml')) as f:
            config = yaml.safe_load(f)
        self.assertFalse(config['NavfnROS']['allow_unknown'])
        self.assertFalse(config['recovery_behavior_enabled'])
        for side in ('global_costmap','local_costmap'):
            grid = config[side]
            self.assertEqual('odom', grid['global_frame'])
            self.assertTrue(grid['rolling_window'])
            self.assertTrue(grid['track_unknown_space'])
            self.assertTrue(grid['always_send_full_costmap'])
            self.assertFalse(grid['static_map'])
            self.assertNotIn('StaticLayer', str(grid['plugins']))
            self.assertTrue(grid['obstacles']['scan']['marking'])
            self.assertTrue(grid['obstacles']['scan']['clearing'])
