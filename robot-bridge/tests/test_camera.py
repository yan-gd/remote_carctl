import io
import os
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
sys.path.insert(0,os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from scout_camera_uplink import Uplink, jpeg_frames, MAX_FRAME

JPEG=b'\xff\xd8real-test-frame\xff\xd9'

class CameraTest(unittest.TestCase):
    def test_jpeg_parser_handles_split_markers_multiple_frames_and_bad_oversized_input(self):
        class TinyPipe(io.BytesIO):
            def read(self,n): return super().read(min(n,3))
        self.assertEqual([JPEG,JPEG],list(jpeg_frames(TinyPipe(b'noise'+JPEG+JPEG))))
        oversized=b'\xff\xd8'+b'a'*MAX_FRAME+b'\xff\xd9'+JPEG
        self.assertEqual([JPEG],list(jpeg_frames(io.BytesIO(oversized))))

    def test_device_credentials_require_https_and_no_credentials_in_url(self):
        for url in ('http://47.109.132.238:8080','https://user:pw@example.com','https://x/?key=secret'):
            with self.assertRaises(ValueError): Uplink(url,'k'*32)
        with self.assertRaises(ValueError): Uplink('https://example.com','short')
        with self.assertRaises(ValueError): Uplink('https://example.com','k'*32,0)

    def test_queue_is_bounded_and_only_latest_frame_is_forwarded_with_device_header(self):
        calls=[]
        uplink=None
        class Handler(BaseHTTPRequestHandler):
            def do_PUT(self):
                calls.append((self.path,self.headers.get('X-Device-Key'),self.rfile.read(int(self.headers['Content-Length']))))
                self.send_response(200);self.end_headers();self.wfile.write(b'{}')
                uplink.stop.set()
            def log_message(self,*args): pass
        server=HTTPServer(('127.0.0.1',0),Handler)
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        try:
            uplink=Uplink('http://127.0.0.1:%s'%server.server_port,'k'*32)
            uplink.offer(b'old-frame');uplink.offer(JPEG)
            self.assertEqual(1,uplink.pending.qsize())
            uplink.run()
            self.assertEqual([('/api/v1/device/camera/frame','k'*32,JPEG)],calls)
            self.assertEqual(0,uplink.pending.qsize())
        finally:
            server.shutdown();server.server_close();worker.join(2)
