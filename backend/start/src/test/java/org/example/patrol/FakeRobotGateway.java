package org.example.patrol;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for the MQTT link in API tests. Plain volatile fields instead of a Mockito mock: the
 * monitor's scheduler thread reads the link state while tests change it.
 */
public class FakeRobotGateway implements RobotGateway {
    volatile boolean connected = true;
    volatile BusinessException failure;
    final List<RobotCommand> published = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public long publish(RobotCommand command, Instant issuedAt) {
        var fail = failure;
        if (fail != null) throw fail;
        published.add(command);
        return seq.incrementAndGet();
    }

    @Override
    public void listen(RobotListener listener) {
        // Tests drive RobotMonitor directly.
    }
}
