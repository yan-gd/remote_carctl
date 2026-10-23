package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

public interface LogGateway {
    void appendLog(ControlLog log);

    Page<ControlLog> logs(
            int page,
            int size,
            String operation,
            java.time.Instant from,
            java.time.Instant to,
            Long actorId);
}
