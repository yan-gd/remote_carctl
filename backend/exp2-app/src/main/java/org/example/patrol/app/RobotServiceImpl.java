package org.example.patrol.app;

import org.example.patrol.app.executor.RobotCommandExecutor;
import org.example.patrol.client.RobotModels.*;
import org.example.patrol.client.RobotService;
import org.example.patrol.domain.*;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RobotServiceImpl implements RobotService {
    private static final List<String> COMMANDS =
            Arrays.stream(RobotCommand.values()).map(Enum::name).toList();
    private static final RobotTelemetry NOTHING =
            new RobotTelemetry(MotionState.UNKNOWN, null, null, null, null, null, null, null);

    private final RobotMonitor monitor;
    private final RobotCommandExecutor commands;
    private final RobotSettings settings;

    public RobotServiceImpl(
            RobotMonitor monitor, RobotCommandExecutor commands, RobotSettings settings) {
        this.monitor = monitor;
        this.commands = commands;
        this.settings = settings;
    }

    public RobotView robot() {
        var s = monitor.snapshot();
        return new RobotView(
                settings.id(),
                settings.name(),
                settings.model(),
                COMMANDS,
                s.online(),
                s.mqttConnected(),
                s.lastSeenAt());
    }

    public RobotStatusView status() {
        var s = monitor.snapshot();
        var t = s.telemetry() == null ? NOTHING : s.telemetry();
        var ack = s.lastAck();
        return new RobotStatusView(
                settings.id(),
                s.online(),
                t.motionState().name(),
                t.batteryPct(),
                t.batteryVoltage(),
                t.linearVelocity(),
                t.angularVelocity(),
                new PositionView(t.x(), t.y(), t.yaw()),
                s.mqttConnected(),
                s.chassisConnected(),
                s.lastSeenAt(),
                s.telemetryAt(),
                ack == null
                        ? null
                        : new AckView(
                                ack.seq(), ack.command(), ack.result(), ack.reason(), s.lastAckAt()));
    }

    public ControlResultView control(RobotControlCommand c, long actor) {
        return commands.control(c, actor);
    }
}
