package org.example.patrol.domain;

import java.time.Instant;

public record ControlLog(
        Long id,
        Long actorId,
        String actorName,
        String operation,
        String target,
        String result,
        Instant occurredAt,
        String error) {}
