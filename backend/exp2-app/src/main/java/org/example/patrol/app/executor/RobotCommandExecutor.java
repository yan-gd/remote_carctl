package org.example.patrol.app.executor;

import org.example.patrol.app.*;
import org.example.patrol.client.RobotModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates and publishes one control command. Deliberately not transactional: SQLite IMMEDIATE
 * transactions would hold the write lock while waiting for the broker.
 *
 * <p>Order of checks: role, command whitelist, MONITOR may only STOP, MQTT link (503), robot online
 * for motion commands (409). STOP is never blocked by the online check.
 */
@Component
public class RobotCommandExecutor {
    static final String OPERATION = "ROBOT_CONTROL";
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RobotCommandExecutor.class);

    private final RobotGateway gateway;
    private final RobotMonitor monitor;
    private final UserGateway users;
    private final AuditService audit;
    private final RobotSettings settings;
    private final Clock clock;
    /** Last time each actor/command(/error) was seen; repeats inside the window are keepalives. */
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    public RobotCommandExecutor(
            RobotGateway gateway,
            RobotMonitor monitor,
            UserGateway users,
            AuditService audit,
            RobotSettings settings,
            Clock clock) {
        this.gateway = gateway;
        this.monitor = monitor;
        this.users = users;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
    }

    public ControlResultView control(RobotControlCommand c, long actor) {
        var user = users.userById(actor);
        String requested = c == null ? null : c.command();
        try {
            var command = RobotCommand.parse(requested);
            RolePolicy.require(user, command == RobotCommand.STOP ? Permission.STOP : Permission.MOVE);
            if (!gateway.connected())
                throw BusinessException.unavailable("MQTT_UNAVAILABLE", "MQTT服务不可用，命令未下发");
            if (command.isMotion() && !monitor.online())
                throw new BusinessException(
                        BusinessException.Kind.CONFLICT, "ROBOT_OFFLINE", "机器人离线，拒绝运动命令");
            Instant now = clock.instant();
            long seq = gateway.publish(command, now);
            if (firstInWindow(actor + ":" + command, now))
                record(() -> audit.success(user, OPERATION, target(command.name())));
            return new ControlResultView(true, command.name(), seq, now);
        } catch (BusinessException e) {
            String label = label(requested);
            if (firstInWindow(actor + ":" + label + ":" + e.getCode(), clock.instant()))
                record(
                        () ->
                                audit.failure(
                                        user == null ? null : user.id(),
                                        user == null ? "anonymous" : user.username(),
                                        OPERATION,
                                        target(label),
                                        e.getCode()));
            throw e;
        }
    }

    private boolean firstInWindow(String key, Instant now) {
        Instant previous = lastSeen.put(key, now);
        return previous == null
                || Duration.between(previous, now).compareTo(settings.keepaliveWindow()) > 0;
    }

    /**
     * Once a command is on the wire its outcome must be reported truthfully, so an unavailable
     * audit store is logged instead of turning an accepted command into an error.
     */
    private void record(Runnable entry) {
        try {
            entry.run();
        } catch (RuntimeException e) {
            log.warn("Robot control audit unavailable ({})", e.getClass().getSimpleName());
        }
    }

    private String target(String command) {
        return "robot:" + settings.id() + ":" + command;
    }

    /** Never copy arbitrary request text into the log table. */
    private static String label(String requested) {
        for (RobotCommand command : RobotCommand.values())
            if (command.name().equals(requested)) return requested;
        return "INVALID";
    }
}
