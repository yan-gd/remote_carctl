package org.example.patrol.domain;

/**
 * One status report from the robot. Values the robot cannot measure stay null; non-finite or
 * out-of-range numbers are dropped to null instead of being guessed.
 */
public record RobotTelemetry(
        MotionState motionState,
        Double batteryPct,
        Double batteryVoltage,
        Double linearVelocity,
        Double angularVelocity,
        Double x,
        Double y,
        Double yaw) {
    public RobotTelemetry {
        motionState = motionState == null ? MotionState.UNKNOWN : motionState;
        batteryPct = within(batteryPct, 0, 100);
        batteryVoltage = within(batteryVoltage, 0, 100);
        linearVelocity = within(linearVelocity, -10, 10);
        angularVelocity = within(angularVelocity, -10, 10);
        x = within(x, -1e6, 1e6);
        y = within(y, -1e6, 1e6);
        yaw = within(yaw, -Math.PI, Math.PI);
    }

    private static Double within(Double value, double min, double max) {
        return value != null && Double.isFinite(value) && value >= min && value <= max
                ? value
                : null;
    }
}
