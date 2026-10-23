package org.example.patrol.app;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.LogGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Clock;

@Service
public class AuditService {
    private final LogGateway logs;
    private final Clock clock;

    public AuditService(LogGateway logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    /** Successful mutations use the caller's transaction: data and audit commit together. */
    public void success(User actor, String operation, String target) {
        record(
                actor == null ? null : actor.id(),
                actor == null ? "anonymous" : actor.username(),
                operation,
                target,
                "SUCCESS",
                null);
    }

    /**
     * Called after a failed business transaction, or by security handlers outside a transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(
            Long actorId, String actorName, String operation, String target, String errorCode) {
        record(actorId, actorName, operation, target, "FAILURE", errorCode);
    }

    private void record(
            Long id, String name, String op, String target, String result, String error) {
        logs.appendLog(new ControlLog(null, id, name, op, target, result, clock.instant(), error));
    }
}
