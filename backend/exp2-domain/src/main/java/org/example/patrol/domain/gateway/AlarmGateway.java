package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

public interface AlarmGateway {
    Alarm alarm(long id);

    void acknowledge(Alarm alarm);

    Page<Alarm> alarms(
            int page,
            int size,
            AlarmLevel level,
            AlarmStatus status,
            java.time.Instant from,
            java.time.Instant to);

    void seedDemoAlarm();

    /** Persist a system-raised alarm and return it with its generated id. */
    Alarm insertAlarm(Alarm alarm);
}
