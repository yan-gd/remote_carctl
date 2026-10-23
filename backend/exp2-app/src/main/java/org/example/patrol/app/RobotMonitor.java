package org.example.patrol.app;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * In-memory robot state built from heartbeats, status reports and receipts. Online means: the MQTT
 * link is up and the last heartbeat said online and arrived within the heartbeat timeout (backend
 * receive time; the robot's clock is not trusted).
 *
 * <p>Alarms are raised once per transition (link lost, robot went offline, chassis silent, battery
 * low), never repeatedly for a condition that persists. Keepalives are never persisted.
 */
@Service
public class RobotMonitor implements RobotListener {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RobotMonitor.class);
    private static final double PCT_HYSTERESIS = 5;
    private static final double VOLTAGE_HYSTERESIS = 0.3;

    private final RobotGateway gateway;
    private final AlarmGateway alarms;
    private final RobotSettings settings;
    private final Clock clock;

    // All fields below are guarded by this.
    private Boolean linkUp; // null until the first connection result is known
    private boolean beating;
    private String offlineReason;
    private Instant lastHeartbeatAt;
    private Boolean chassisConnected;
    private RobotTelemetry telemetry;
    private Instant telemetryAt;
    private RobotAck lastAck;
    private Instant lastAckAt;
    private boolean online;
    private boolean batteryLow;

    public RobotMonitor(
            RobotGateway gateway, AlarmGateway alarms, RobotSettings settings, Clock clock) {
        this.gateway = gateway;
        this.alarms = alarms;
        this.settings = settings;
        this.clock = clock;
        gateway.listen(this);
    }

    public record Snapshot(
            boolean online,
            boolean mqttConnected,
            Instant lastSeenAt,
            Boolean chassisConnected,
            RobotTelemetry telemetry,
            Instant telemetryAt,
            RobotAck lastAck,
            Instant lastAckAt) {}

    @Override
    public void onLink(boolean connected) {
        raise(link(connected));
    }

    @Override
    public void onHeartbeat(RobotHeartbeat heartbeat) {
        raise(heartbeat(heartbeat));
    }

    @Override
    public void onStatus(RobotTelemetry report) {
        raise(status(report));
    }

    @Override
    public synchronized void onAck(RobotAck ack) {
        lastAck = ack;
        lastAckAt = clock.instant();
    }

    /** A heartbeat timeout produces no message, so the online state is also polled. */
    @Scheduled(fixedDelayString = "${patrol.robot.check-interval-ms:500}")
    public void evaluate() {
        raise(transitions());
    }

    public synchronized boolean online() {
        return isOnline(clock.instant());
    }

    /** Telemetry is only returned while online and fresh; stale values are never shown as live. */
    public synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        boolean up = isOnline(now);
        boolean fresh =
                up
                        && telemetryAt != null
                        && !now.isAfter(telemetryAt.plus(settings.heartbeatTimeout()));
        return new Snapshot(
                up,
                gateway.connected(),
                lastHeartbeatAt,
                up ? chassisConnected : null,
                fresh ? telemetry : null,
                telemetryAt,
                lastAck,
                lastAckAt);
    }

    private boolean isOnline(Instant now) {
        return gateway.connected()
                && beating
                && lastHeartbeatAt != null
                && !now.isAfter(lastHeartbeatAt.plus(settings.heartbeatTimeout()));
    }

    private synchronized List<Alarm> link(boolean connected) {
        var raised = new ArrayList<Alarm>();
        if (!connected) {
            if (!Boolean.FALSE.equals(linkUp))
                raised.add(
                        alarm(
                                "MQTT连接断开",
                                "后端无法连接MQTT Broker，控制命令返回503；客户端正在自动重连",
                                AlarmLevel.CRITICAL,
                                "MQTT"));
            beating = false; // after reconnecting, only a fresh heartbeat counts
        }
        linkUp = connected;
        raised.addAll(transitions());
        return raised;
    }

    private synchronized List<Alarm> heartbeat(RobotHeartbeat heartbeat) {
        var raised = new ArrayList<Alarm>();
        beating = heartbeat.online();
        if (beating) {
            lastHeartbeatAt = clock.instant();
            offlineReason = null;
            Boolean previous = chassisConnected;
            chassisConnected = heartbeat.chassisConnected();
            if (Boolean.FALSE.equals(chassisConnected) && !Boolean.FALSE.equals(previous))
                raised.add(
                        alarm(
                                "底盘无状态数据",
                                "桥接节点在线但收不到底盘状态（/scout_status），请检查CAN总线与scout_base节点",
                                AlarmLevel.WARNING,
                                settings.id()));
        } else {
            offlineReason = heartbeat.reason();
            chassisConnected = null;
        }
        raised.addAll(transitions());
        return raised;
    }

    private synchronized List<Alarm> status(RobotTelemetry report) {
        telemetry = report;
        telemetryAt = clock.instant();
        var raised = new ArrayList<Alarm>();
        Boolean low = batteryLow(report);
        if (low != null) {
            if (low && !batteryLow) raised.add(batteryAlarm(report));
            batteryLow = low;
        }
        return raised;
    }

    private synchronized List<Alarm> transitions() {
        boolean up = isOnline(clock.instant());
        var raised = new ArrayList<Alarm>();
        // When our own MQTT link is down the robot state is unknown; the link alarm covers it.
        if (online && !up && gateway.connected()) raised.add(offlineAlarm());
        if (!up) chassisConnected = null;
        online = up;
        return raised;
    }

    private Boolean batteryLow(RobotTelemetry t) {
        if (settings.lowBatteryPct() != null && t.batteryPct() != null)
            return t.batteryPct()
                    < settings.lowBatteryPct() + (batteryLow ? PCT_HYSTERESIS : 0);
        if (settings.lowBatteryVoltage() != null && t.batteryVoltage() != null)
            return t.batteryVoltage()
                    < settings.lowBatteryVoltage() + (batteryLow ? VOLTAGE_HYSTERESIS : 0);
        return null;
    }

    private Alarm batteryAlarm(RobotTelemetry t) {
        String detail =
                settings.lowBatteryPct() != null && t.batteryPct() != null
                        ? String.format("电量%.0f%%，低于阈值%.0f%%", t.batteryPct(), settings.lowBatteryPct())
                        : String.format(
                                "电池电压%.2fV，低于阈值%.2fV",
                                t.batteryVoltage(), settings.lowBatteryVoltage());
        return alarm("电量过低", detail + "，请尽快返回充电", AlarmLevel.WARNING, settings.id());
    }

    private Alarm offlineAlarm() {
        if (!beating && "SHUTDOWN".equals(offlineReason))
            return alarm(
                    "小车离线", "桥接节点已正常退出，运动命令已禁止", AlarmLevel.WARNING, settings.id());
        String detail =
                beating
                        ? "超过" + settings.heartbeatTimeout().toMillis() + "毫秒未收到心跳"
                        : "LWT".equals(offlineReason)
                                ? "桥接节点异常断开（MQTT遗嘱消息）"
                                : "桥接节点报告离线（"
                                        + Objects.requireNonNullElse(offlineReason, "未说明原因")
                                        + "）";
        return alarm(
                "小车离线",
                detail + "，已标记离线并禁止运动命令；小车侧看门狗负责停车",
                AlarmLevel.CRITICAL,
                settings.id());
    }

    private Alarm alarm(String title, String description, AlarmLevel level, String source) {
        return Alarm.raise(title, description, level, source, clock.instant());
    }

    /** Stored outside the monitor lock so a slow database never blocks status queries. */
    private void raise(List<Alarm> raised) {
        for (Alarm alarm : raised) {
            try {
                alarms.insertAlarm(alarm);
                log.warn("Robot alarm raised: {} [{}]", alarm.title(), alarm.source());
            } catch (RuntimeException e) {
                log.error(
                        "Robot alarm could not be stored: {} ({})",
                        alarm.title(),
                        e.getClass().getSimpleName());
            }
        }
    }
}
