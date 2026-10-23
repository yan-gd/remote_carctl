package org.example.patrol.domain;

import java.time.Instant;

public record Alarm(
        Long id,
        String title,
        String description,
        AlarmLevel level,
        AlarmStatus status,
        String source,
        Instant occurredAt,
        Long acknowledgedBy,
        Instant acknowledgedAt) {
    /** A new, unacknowledged alarm raised by the system itself (e.g. robot heartbeat lost). */
    public static Alarm raise(
            String title, String description, AlarmLevel level, String source, Instant at) {
        if (title == null || title.isBlank() || level == null || source == null || at == null)
            throw BusinessException.invalid("告警标题、级别、来源和时间不能为空");
        return new Alarm(
                null, title, description, level, AlarmStatus.UNACKNOWLEDGED, source, at, null, null);
    }

    public Alarm acknowledge(long actor, Instant now) {
        if (status != AlarmStatus.UNACKNOWLEDGED) throw BusinessException.conflict("告警已确认，请勿重复确认");
        if (actor <= 0) throw BusinessException.invalid("确认人非法");
        return new Alarm(
                id,
                title,
                description,
                level,
                AlarmStatus.ACKNOWLEDGED,
                source,
                occurredAt,
                actor,
                now);
    }
}
