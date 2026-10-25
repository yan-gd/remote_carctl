#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Real camera -> HTTPS JPEG relay. No files, no recordings, bounded/drop-old mailbox.

ROS compressed images avoid cv_bridge's Python2/3 native-module ABI mismatch on Melodic.
Alternatively read a UVC device through ffmpeg's stdout pipe (no shell).
"""
import argparse
import http.client
import logging
import os
import queue
import signal
import ssl
import subprocess
import threading
import time
from urllib.parse import urlsplit

MAX_FRAME = 384 * 1024
LOG = logging.getLogger('scout_camera')


def jpeg_frames(pipe):
    """Parse ffmpeg MJPEG with bounded memory even for an oversized/broken frame."""
    buffer = bytearray()
    while True:
        block = pipe.read(4096)
        if not block:
            return
        buffer.extend(block)
        while True:
            start = buffer.find(b'\xff\xd8')
            if start < 0:
                buffer[:] = buffer[-1:]
                break
            if start:
                del buffer[:start]
            end = buffer.find(b'\xff\xd9', 2)
            if end < 0:
                if len(buffer) > MAX_FRAME:
                    buffer[:] = buffer[-1:]
                break
            frame = bytes(buffer[:end + 2])
            del buffer[:end + 2]
            if len(frame) <= MAX_FRAME:
                yield frame


class Uplink:
    def __init__(self, base_url, key, fps=8):
        self.url = urlsplit(base_url)
        local = self.url.hostname in ('127.0.0.1', 'localhost', '::1')
        if self.url.scheme != 'https' and not (self.url.scheme == 'http' and local):
            raise ValueError('Use HTTPS, or HTTP only on loopback for isolated local tests')
        if self.url.username or self.url.password or self.url.query or self.url.fragment:
            raise ValueError('Credentials/query/fragment are not allowed in the server URL')
        if not self.url.hostname or len(key) < 32 or len(key) > 256:
            raise ValueError('A server hostname and a 32-256 character device key are required')
        self.key = key
        if isinstance(fps, bool) or not isinstance(fps, int) or not 1 <= fps <= 10:
            raise ValueError('FPS must be between 1 and 10')
        self.fps = fps
        self.pending = queue.Queue(maxsize=1)
        self.stop = threading.Event()
        self.connection = None

    def offer(self, frame):
        if len(frame) < 4 or len(frame) > MAX_FRAME:
            return
        try:
            self.pending.get_nowait()
        except queue.Empty:
            pass
        try:
            self.pending.put_nowait((time.monotonic(), bytes(frame)))
        except queue.Full:
            pass

    def run(self):
        next_frame = 0
        next_warning = 0
        while not self.stop.is_set():
            try:
                captured, frame = self.pending.get(timeout=0.5)
            except queue.Empty:
                continue
            if time.monotonic() - captured > 1:
                continue
            if self.stop.wait(max(0, next_frame - time.monotonic())):
                break
            next_frame = time.monotonic() + 1 / self.fps
            try:
                if self.connection is None:
                    if self.url.scheme == 'https':
                        self.connection = http.client.HTTPSConnection(self.url.hostname, self.url.port, timeout=3, context=ssl.create_default_context())
                    else:
                        self.connection = http.client.HTTPConnection(self.url.hostname, self.url.port, timeout=3)
                path = self.url.path.rstrip('/') + '/api/v1/device/camera/frame'
                self.connection.request('PUT', path, frame, {
                    'Content-Type': 'image/jpeg', 'X-Device-Key': self.key,
                    'Content-Length': str(len(frame)), 'Cache-Control': 'no-store'})
                response = self.connection.getresponse()
                response.read(8192)
                if response.status != 200:
                    raise RuntimeError('HTTP %s' % response.status)
            except Exception as error:
                if self.connection:
                    self.connection.close()
                self.connection = None
                if time.monotonic() >= next_warning:
                    # Never log body, headers or keys.
                    LOG.warning('Frame relay unavailable (%s)', type(error).__name__)
                    next_warning = time.monotonic() + 10
                self.stop.wait(1)
        if self.connection:
            self.connection.close()
        with self.pending.mutex:
            self.pending.queue.clear()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True, help='HTTPS backend public origin')
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--ros-topic', help='sensor_msgs/CompressedImage topic')
    source.add_argument('--device', help='actual UVC color device, e.g. /dev/video2')
    parser.add_argument('--fps', type=int, default=8, choices=range(1, 11))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    uplink = Uplink(args.server, os.environ.get('PATROL_CAMERA_DEVICE_KEY', ''), args.fps)
    for name in ('SIGINT', 'SIGTERM'):
        signal.signal(getattr(signal, name), lambda *_: uplink.stop.set())
    worker = threading.Thread(target=uplink.run, daemon=True)
    worker.start()
    process = None
    try:
        if args.ros_topic:
            import rospy
            from sensor_msgs.msg import CompressedImage
            rospy.init_node('scout_camera_uplink', disable_signals=True)
            def received(msg):
                age = abs((rospy.Time.now() - msg.header.stamp).to_sec())
                if age <= 1.5 and 'jpeg' in msg.format.lower():
                    uplink.offer(msg.data)
            rospy.Subscriber(args.ros_topic, CompressedImage, received,
                             queue_size=1, buff_size=MAX_FRAME)
            LOG.info('Reading real compressed camera topic %s', args.ros_topic)
            while not uplink.stop.wait(0.2) and not rospy.is_shutdown():
                pass
        else:
            if not args.device.startswith('/dev/video'):
                raise ValueError('--device must name an actual /dev/video* camera')
            process = subprocess.Popen([
                'ffmpeg', '-nostdin', '-loglevel', 'error', '-f', 'v4l2',
                '-video_size', '640x480', '-framerate', str(args.fps), '-i', args.device,
                '-an', '-vf', 'scale=640:-2', '-c:v', 'mjpeg', '-q:v', '8',
                '-f', 'image2pipe', 'pipe:1'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            # SIGTERM must also release a blocking stdout read if the device has stopped.
            def reap():
                uplink.stop.wait()
                if process.poll() is None:
                    process.terminate()
            threading.Thread(target=reap, daemon=True).start()
            LOG.info('Reading real UVC camera %s', args.device)
            for frame in jpeg_frames(process.stdout):
                if uplink.stop.is_set():
                    break
                uplink.offer(frame)
            if not uplink.stop.is_set():
                raise RuntimeError('Camera/ffmpeg stopped; check device formats')
    finally:
        uplink.stop.set()
        if process and process.poll() is None:
            process.terminate()
        worker.join(timeout=5)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
