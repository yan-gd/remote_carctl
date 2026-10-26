import base64
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace as S
from unittest.mock import Mock, patch
import yaml
from scout_mapping import map_display, save_grid, saved_map, validate_settings, WebMapping
from scout_bridge_core import BridgeCore, TelemetryCache, load_config


def grid(w=2, h=2, data=None):
    return S(info=S(width=w, height=h, resolution=0.05,
                    origin=S(position=S(x=-1., y=2.), orientation=S(z=0., w=1.))),
             data=data if data is not None else [0, 100, -1, 0])


class MappingTests(unittest.TestCase):
    def test_map_image_preserves_ros_order_and_conservative_obstacles(self):
        result = map_display(grid(), 'id', 'session')
        self.assertEqual(base64.b64decode(result['cells']), bytes([1, 2, 0, 1]))
        # A single occupied/unknown pixel cannot disappear through reduction.
        raw = [0]*1026
        raw[0], raw[4] = 100, -1
        result = map_display(grid(513, 2, raw), 'id', 'session')
        cells = base64.b64decode(result['cells'])
        self.assertEqual(result['width'], 257)
        self.assertEqual(cells[0], 2)
        self.assertEqual(cells[2], 0)

    def test_map_saves_standard_pgm_yaml_without_overwriting_or_path_escape(self):
        with tempfile.TemporaryDirectory() as folder:
            target = save_grid(folder, 'lab', grid())
            meta = yaml.safe_load(target.read_text())
            self.assertEqual(meta['origin'], [-1., 2., 0.])
            self.assertEqual(meta['image'], 'map.pgm')
            self.assertTrue((target.parent/'map.pgm').read_bytes().endswith(bytes([205, 254, 254, 0])))
            before = target.read_bytes()
            with self.assertRaises(FileExistsError):
                save_grid(folder, 'lab', grid())
            self.assertEqual(before, target.read_bytes())
            for name in ('../x', '/tmp/x', 'a/b', '', 'a;echo', 'a'*41):
                with self.assertRaises(ValueError):
                    saved_map(folder, name)

    def test_secrets_require_https_origin_and_no_redirect(self):
        for origin in ('http://example.com', 'https://example.com/api', 'https://u:p@example.com', 'https://example.com?q=1'):
            with self.assertRaises(ValueError):
                validate_settings(origin, 'a'*32)
        validate_settings('https://example.com', 'a'*32)
        validate_settings('http://127.0.0.1:8080', 'a'*32)

    def test_stale_wrong_session_and_duplicate_commands_do_not_execute(self):
        manager = WebMapping.__new__(WebMapping)
        manager.stop, manager.busy, manager.session, manager.seen = threading.Event(), False, 'current', set()
        manager._execute = Mock()
        now = time.time()*1000
        for session, expires in [('old', now+9000), ('current', now-1), ('current', now+30000)]:
            manager.accept(dict(id='x', session=session, expiresAt=expires, request={'action': 'START'}))
        manager._execute.assert_not_called()
        with patch('scout_mapping.threading.Thread') as thread:
            command = dict(id='valid', session='current', expiresAt=now+9000, request={'action':'START'})
            manager.accept(command)
            manager.busy = False
            manager.accept(command)
            self.assertEqual(thread.call_count, 1)

    def test_map_transition_rejects_motion_but_allows_stop_without_waiting_for_worker(self):
        import json
        telemetry = TelemetryCache(3)
        ros = Mock()
        core = BridgeCore(load_config(), ros, telemetry, 'ros1')
        core.web_mapping = S(busy=True, mode='MAPPING')
        core._ack = Mock()
        core.on_control(json.dumps(dict(command='FORWARD', seq=1, session='s')).encode())
        self.assertEqual(core.motion.state, 'STOPPED')
        self.assertEqual(core._ack.call_args[0][-1], 'MAP_OPERATION_IN_PROGRESS')
        core.on_control(json.dumps(dict(command='STOP', seq=2, session='s')).encode())
        ros.publish_velocity.assert_called_with(0.0, 0.0)

    def test_failed_stationary_check_never_launches_pipeline(self):
        manager=WebMapping.__new__(WebMapping)
        manager.lifecycle=threading.RLock()
        manager.stop=Mock();manager.stop.is_set.return_value=False;manager.stop.wait.return_value=True
        manager.core=Mock();manager.core._shutting_down=False;manager.core._command_lock=threading.RLock()
        manager.core.telemetry.report.return_value={'linear_velocity':0.2,'angular_velocity':0}
        manager.core.telemetry.chassis_connected.return_value=True
        manager._launch=Mock()
        manager._execute({'action':'START'})
        manager._launch.assert_not_called()
        self.assertEqual(manager.result,'FAILED')
        self.assertEqual(manager.message,'ROBOT_MUST_BE_STOPPED')

    def test_start_save_load_flow_uses_only_fixed_launch_files_and_saved_map(self):
        with tempfile.TemporaryDirectory() as folder:
            manager=WebMapping.__new__(WebMapping)
            manager.lifecycle=threading.RLock();manager.stop=threading.Event();manager.root=Path(folder)
            manager.core=Mock();manager.core._shutting_down=False;manager.core._command_lock=threading.RLock()
            manager.core.telemetry.report.return_value={'linear_velocity':0.,'angular_velocity':0.}
            manager.core.telemetry.chassis_connected.return_value=True
            manager._launch=Mock();manager._stop_pipeline=Mock();manager._clear_map=Mock();manager._ensure_exclusive=Mock()
            manager._snapshot=Mock(return_value=(grid(),'map-a'))
            manager._execute({'action':'START'})
            self.assertEqual(manager.mode,'MAPPING')
            manager._launch.assert_called_once_with('cloud_mapping.launch')
            manager.core._send.assert_called_with(0.,0.)
            manager._execute({'action':'SAVE','name':'lab'})
            self.assertEqual(manager.result,'SUCCEEDED')
            self.assertTrue((Path(folder)/'lab/map.yaml').is_file())
            manager._execute({'action':'LOAD','name':'lab'})
            self.assertEqual(manager.mode,'NAVIGATION')
            manager._launch.assert_called_with('cloud_navigation.launch','map_yaml:='+str(Path(folder)/'lab/map.yaml'))

    def test_scan_overlay_applies_laser_to_map_rotation_and_translation(self):
        from ros1_tf_pose import scan_cloud
        import math
        scan=S(header=S(stamp=123),ranges=[2.,float('nan'),float('inf')],range_min=0.1,range_max=10.,angle_min=0.,angle_increment=0.1)
        tf=S(transform=S(translation=S(x=1.,y=-1.),rotation=S(x=0.,y=0.,z=math.sin(math.pi/4),w=math.cos(math.pi/4))))
        result=scan_cloud(scan,tf,lambda:S(header=S(),points=[]),lambda:S(),'map')
        self.assertEqual(result.header.frame_id,'map');self.assertEqual(result.header.stamp,123)
        self.assertEqual(len(result.points),1)
        self.assertAlmostEqual(result.points[0].x,1.)
        self.assertAlmostEqual(result.points[0].y,1.)


if __name__ == '__main__':
    unittest.main()
