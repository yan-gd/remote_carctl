#!/usr/bin/env python2
# -*- coding: utf-8 -*-
"""D435 ROS CompressedImage -> Jetson H.264 encoder -> authenticated RTSPS MediaMTX.

Run with the *system* Python 2.7 on this Melodic Xavier (rospy + PyGObject/GI),
not the Conda Python 3.7 bridge interpreter. Never opens the D435 a second time.
No media files, RTSP credentials in argv, or password-bearing stream URLs.
"""
from __future__ import print_function

import argparse
import logging
import os
try:
    import queue
except ImportError:
    import Queue as queue
import re
import signal
import sys
import time
try:
    from urllib.parse import urlsplit
except ImportError:
    from urlparse import urlsplit

LOG = logging.getLogger('scout_h264')
MAX_JPEG = 1024 * 1024


def validate_settings(url, username, password, fps, bitrate):
    parsed = urlsplit(url)
    if parsed.scheme not in ('rtsps', 'rtsp') or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('RTSP_URL must contain a host/path, with no embedded credentials')
    if parsed.scheme != 'rtsps' and parsed.hostname not in ('localhost', '127.0.0.1'):
        raise ValueError('The remote publisher must use encrypted rtsps://')
    if parsed.path != '/SCOUT-001' or parsed.query or parsed.fragment:
        raise ValueError('RTSP_URL path must be /SCOUT-001 without a query or fragment')
    if not re.match(r'^[A-Za-z0-9.-]+$', parsed.hostname) or not 1 <= (parsed.port or 8322) <= 65535:
        raise ValueError('RTSP_URL host and port are invalid')
    if not username or not password or len(password) < 16:
        raise ValueError('Set RTSP_USER and a random RTSP_PASSWORD (>=16 characters)')
    if not 5 <= fps <= 30 or not 200000 <= bitrate <= 2500000:
        raise ValueError('FPS must be 5-30 and bitrate 200000-2500000')
    return parsed


class LatestFrame(object):
    """Drop superseded JPEGs while the encoder or network is busy."""
    def __init__(self):
        self.pending = queue.Queue(maxsize=1)

    def offer(self, frame):
        if len(frame) < 4 or len(frame) > MAX_JPEG or not frame.startswith(b'\xff\xd8') or not frame.endswith(b'\xff\xd9'):
            return False
        try:
            self.pending.get_nowait()
        except queue.Empty:
            pass
        try:
            self.pending.put_nowait((time.time(), frame))
            return True
        except queue.Full:
            return False


def make_pipeline(Gst, url, user, password, fps, bitrate):
    # NVMM is an explicit memory conversion for Jetson's video encoder.  H.264
    # baseline, no B frames, is required for broad browser WebRTC compatibility.
    description = (
        'appsrc name=source is-live=true block=false do-timestamp=true format=time '
        'caps=image/jpeg,framerate={fps}/1 ! '
        'queue max-size-buffers=2 leaky=downstream ! '
        'jpegdec ! videoconvert ! videoscale ! videorate drop-only=true ! '
        'video/x-raw,width=640,height=480,framerate={fps}/1,format=I420 ! '
        'nvvidconv ! video/x-raw(memory:NVMM),format=NV12 ! '
        'nvv4l2h264enc bitrate={bitrate} profile=0 iframeinterval={interval} '
        'insert-sps-pps=true ! h264parse config-interval=1 ! '
        'rtspclientsink name=publisher protocols=tcp location="{url}"'
    ).format(fps=fps, bitrate=bitrate, interval=fps * 2, url=url)
    pipeline = Gst.parse_launch(description)
    sink = pipeline.get_by_name('publisher')
    sink.set_property('user-id', user)
    sink.set_property('user-pw', password)
    return pipeline, pipeline.get_by_name('source')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ros-topic', default='/camera/color/image_raw/compressed')
    parser.add_argument('--fps', type=int, default=15)
    parser.add_argument('--bitrate', type=int, default=900000, help='H.264 bits per second')
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
    url = os.environ.get('SCOUT_RTSP_URL', '')
    user = os.environ.get('SCOUT_RTSP_USER', '')
    password = os.environ.get('SCOUT_RTSP_PASSWORD', '')
    try:
        validate_settings(url, user, password, args.fps, args.bitrate)
    except ValueError as error:
        LOG.error('%s', error)
        return 2
    try:
        import gi
        gi.require_version('Gst', '1.0')
        from gi.repository import Gst
        import rospy
        from sensor_msgs.msg import CompressedImage
    except ImportError as error:
        LOG.error('Missing system Python2 ROS/GStreamer GI dependency: %s', error)
        return 2

    Gst.init(None)
    frames = LatestFrame()
    rospy.init_node('scout_h264_uplink', disable_signals=True)

    def received(msg):
        try:
            if 'jpeg' not in msg.format.lower() or abs((rospy.Time.now() - msg.header.stamp).to_sec()) > 1.5:
                return
            data = msg.data if isinstance(msg.data, bytes) else bytearray(msg.data).decode('latin1').encode('latin1')
            frames.offer(data)
        except (ValueError, TypeError):
            pass

    rospy.Subscriber(args.ros_topic, CompressedImage, received, queue_size=1, buff_size=MAX_JPEG)
    stopped = [False]
    def stop(*_):
        stopped[0] = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    LOG.info('Starting Jetson H.264 publisher: %d fps, %d bit/s, %s', args.fps, args.bitrate, args.ros_topic)
    # A media-side error must not terminate the ROS chassis/MQTT control loop.
    while not stopped[0] and not rospy.is_shutdown():
        pipeline = None
        try:
            pipeline, appsrc = make_pipeline(Gst, url, user, password, args.fps, args.bitrate)
            bus = pipeline.get_bus()
            if pipeline.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
                raise RuntimeError('GStreamer refused PLAYING')
            next_frame = 0
            while not stopped[0] and not rospy.is_shutdown():
                error = bus.pop_filtered(Gst.MessageType.ERROR | Gst.MessageType.EOS)
                if error:
                    raise RuntimeError('Encoder or RTSP transport stopped (%s)' % error.type.value_nick)
                try:
                    captured, jpeg = frames.pending.get(timeout=0.1)
                except queue.Empty:
                    continue
                now = time.time()
                if now - captured > 1 or now < next_frame:
                    continue
                next_frame = now + 1.0 / args.fps
                buffer = Gst.Buffer.new_allocate(None, len(jpeg), None)
                buffer.fill(0, jpeg)
                result = appsrc.emit('push-buffer', buffer)
                if result != Gst.FlowReturn.OK:
                    raise RuntimeError('GStreamer appsrc flow stopped (%s)' % result.value_nick)
        except Exception as error:
            LOG.warning('H.264 stream unavailable (%s); retrying', type(error).__name__)
        finally:
            if pipeline is not None:
                pipeline.set_state(Gst.State.NULL)
        if not stopped[0] and not rospy.is_shutdown():
            time.sleep(3)
    return 0


if __name__ == '__main__':
    sys.exit(main())
