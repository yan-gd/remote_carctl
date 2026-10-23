package org.example.patrol.app;

import java.time.Duration;

/**
 * Robot identity and monitoring policy (patrol.robot.* properties).
 *
 * @param keepaliveWindow a repeat of the same command by the same user within this window is a
 *     keepalive and is not written to the control log again
 * @param lowBatteryPct alarm threshold when the robot reports a percentage; null disables it
 * @param lowBatteryVoltage alarm threshold when only a voltage is reported; null disables it
 */
public record RobotSettings(
        String id,
        String name,
        String model,
        Duration heartbeatTimeout,
        Duration keepaliveWindow,
        Double lowBatteryPct,
        Double lowBatteryVoltage) {
    public RobotSettings {
        // The id is part of every MQTT topic, so MQTT wildcards and separators are not allowed.
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("patrol.robot.id 只能包含1-64位字母、数字、下划线或连字符");
        if (heartbeatTimeout == null || heartbeatTimeout.isNegative() || heartbeatTimeout.isZero())
            throw new IllegalArgumentException("patrol.robot.heartbeat-timeout-ms 须为正数");
        if (keepaliveWindow == null || keepaliveWindow.isNegative())
            throw new IllegalArgumentException("patrol.robot.keepalive-window-ms 不能为负数");
    }
}
