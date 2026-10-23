package org.example.patrol.app.executor;

import org.example.patrol.app.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class AlarmCommandExecutor {
    private final AlarmGateway alarms;
    private final UserGateway users;
    private final AuditService audit;
    private final Clock clock;

    public AlarmCommandExecutor(
            AlarmGateway alarms, UserGateway users, AuditService audit, Clock clock) {
        this.alarms = alarms;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AlarmView acknowledge(long id, long actor) {
        var u = users.userById(actor);
        RolePolicy.require(u, Role.MONITOR);
        var a = Views.required(alarms.alarm(id), "告警").acknowledge(actor, clock.instant());
        alarms.acknowledge(a);
        audit.success(u, "ALARM_ACK", "alarm:" + id);
        return Views.alarm(a);
    }
}
