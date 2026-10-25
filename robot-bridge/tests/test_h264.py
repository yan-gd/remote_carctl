import importlib.util
import os
import unittest

MODULE = os.path.join(os.path.dirname(__file__), '..', 'scout_h264_uplink.py')
spec = importlib.util.spec_from_file_location('scout_h264_uplink', MODULE)
uplink = importlib.util.module_from_spec(spec)
spec.loader.exec_module(uplink)


class H264UplinkTest(unittest.TestCase):
    def test_encrypted_publish_url_and_separate_credentials(self):
        uplink.validate_settings('rtsps://agilex.yoyoyan.cn:8322/SCOUT-001',
                                 'robot_publisher', 'A' * 24, 15, 900000)
        for url in ('rtsp://example.org:8554/SCOUT-001',
                    'rtsps://user:pass@example.org/SCOUT-001',
                    'rtsps://example.org/other',
                    'rtsps://example.org/SCOUT-001?secret=x'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                uplink.validate_settings(url, 'robot_publisher', 'A' * 24, 15, 900000)

    def test_bounded_latest_jpeg(self):
        frames = uplink.LatestFrame()
        first = b'\xff\xd8first\xff\xd9'
        last = b'\xff\xd8last\xff\xd9'
        self.assertTrue(frames.offer(first))
        self.assertTrue(frames.offer(last))
        self.assertEqual(frames.pending.get_nowait()[1], last)
        self.assertFalse(frames.offer(b'invalid'))


if __name__ == '__main__':
    unittest.main()
