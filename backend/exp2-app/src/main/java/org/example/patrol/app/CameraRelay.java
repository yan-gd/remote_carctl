package org.example.patrol.app;

import org.example.patrol.domain.BusinessException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Transient fan-out only. No latest-frame store, repository, files, or history. */
@Service
public class CameraRelay {
    public static final int MAX_FRAME_BYTES = 384 * 1024;
    public static final int MAX_VIEWERS = 8;
    private final Set<Subscription> viewers = new HashSet<>();
    private long lastFrameNanos;
    private long forwarded;

    public record Status(boolean online, int viewers, long forwardedFrames) {}
    private record Frame(byte[] jpeg, long received) {}

    public synchronized Status status() {
        return new Status(lastFrameNanos != 0 && System.nanoTime() - lastFrameNanos < 3_000_000_000L,
                viewers.size(), forwarded);
    }

    public synchronized Subscription subscribe() {
        if (viewers.size() >= MAX_VIEWERS)
            throw BusinessException.unavailable("CAMERA_BUSY", "摄像头观看连接已达上限，请稍后重试");
        var subscription = new Subscription();
        viewers.add(subscription);
        return subscription;
    }

    public synchronized int accept(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > MAX_FRAME_BYTES
                || jpeg[0] != (byte) 0xff || jpeg[1] != (byte) 0xd8
                || jpeg[jpeg.length - 2] != (byte) 0xff || jpeg[jpeg.length - 1] != (byte) 0xd9)
            throw BusinessException.invalid("需要有效的JPEG帧，大小不得超过384KiB");
        lastFrameNanos = System.nanoTime();
        // With no viewers the byte array is immediately discarded.
        var frame = new Frame(jpeg, lastFrameNanos);
        for (var viewer : viewers) {
            viewer.pending.clear(); // Drop an old frame rather than build a recording backlog.
            viewer.pending.offer(frame);
        }
        if (!viewers.isEmpty()) forwarded++;
        return viewers.size();
    }

    public final class Subscription implements AutoCloseable {
        private final ArrayBlockingQueue<Frame> pending = new ArrayBlockingQueue<>(1);
        private boolean closed;

        public byte[] poll(long timeoutMs) throws InterruptedException {
            Frame frame = pending.poll(timeoutMs, TimeUnit.MILLISECONDS);
            // A slow viewer must never be shown an old frame as live.
            return frame == null || System.nanoTime() - frame.received() > 1_000_000_000L
                    ? null : frame.jpeg();
        }

        @Override
        public void close() {
            synchronized (CameraRelay.this) {
                if (closed) return;
                closed = true;
                viewers.remove(this);
                pending.clear();
            }
        }
    }
}
