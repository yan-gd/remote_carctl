package org.example.patrol.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

class RobotDomainTest {
    final Instant now = Instant.parse("2026-10-23T00:00:00Z");

    @Test
    void commandsAreAStrictWhitelist() {
        assertEquals(RobotCommand.FORWARD, RobotCommand.parse("FORWARD"));
        assertEquals(RobotCommand.STOP, RobotCommand.parse("STOP"));
        assertTrue(RobotCommand.LEFT.isMotion());
        assertFalse(RobotCommand.STOP.isMotion());
        for (String bad : Arrays.asList(null, "", "forward", "HOME", " STOP", "JUMP"))
            assertEquals(
                    "INVALID_COMMAND",
                    assertThrows(BusinessException.class, () -> RobotCommand.parse(bad)).getCode());
    }

    @Test
    void unknownMotionStateIsNotGuessed() {
        assertEquals(MotionState.FORWARD, MotionState.of("FORWARD"));
        assertEquals(MotionState.STOPPED, MotionState.of("STOPPED"));
        assertEquals(MotionState.UNKNOWN, MotionState.of("RUNNING"));
        assertEquals(MotionState.UNKNOWN, MotionState.of(null));
    }

    @Test
    void telemetryDropsInvalidNumbersInsteadOfFakingThem() {
        var t =
                new RobotTelemetry(
                        null, 120.0, Double.NaN, 0.3, Double.POSITIVE_INFINITY, 1.0, null, 4.0);
        assertEquals(MotionState.UNKNOWN, t.motionState());
        assertNull(t.batteryPct());
        assertNull(t.batteryVoltage());
        assertEquals(0.3, t.linearVelocity());
        assertNull(t.angularVelocity());
        assertEquals(1.0, t.x());
        assertNull(t.y());
        assertNull(t.yaw());
        var valid = new RobotTelemetry(MotionState.STOPPED, 82.0, 24.6, 0.0, -0.5, 0.0, 0.0, Math.PI);
        assertEquals(82.0, valid.batteryPct());
        assertEquals(24.6, valid.batteryVoltage());
        assertEquals(Math.PI, valid.yaw());
    }

    @Test
    void systemAlarmsStartUnacknowledged() {
        var a = Alarm.raise("小车离线", "心跳超时", AlarmLevel.CRITICAL, "SCOUT-001", now);
        assertNull(a.id());
        assertEquals(AlarmStatus.UNACKNOWLEDGED, a.status());
        assertEquals(now, a.occurredAt());
        assertNull(a.acknowledgedBy());
        assertThrows(
                BusinessException.class, () -> Alarm.raise(" ", null, AlarmLevel.INFO, "X", now));
        assertThrows(BusinessException.class, () -> Alarm.raise("t", null, null, "X", now));
        assertThrows(
                BusinessException.class, () -> Alarm.raise("t", null, AlarmLevel.INFO, null, now));
        assertThrows(
                BusinessException.class, () -> Alarm.raise("t", null, AlarmLevel.INFO, "X", null));
    }

    @Test
    void requireAnyAcceptsListedRolesOnly() {
        var monitor = new User(1L, "monitor", "", Role.MONITOR, now);
        assertDoesNotThrow(() -> RolePolicy.requireAny(monitor, Role.OPERATOR, Role.MONITOR));
        assertDoesNotThrow(() -> RolePolicy.require(monitor, Role.MONITOR));
        assertEquals(
                BusinessException.Kind.FORBIDDEN,
                assertThrows(
                                BusinessException.class,
                                () -> RolePolicy.requireAny(monitor, Role.OPERATOR))
                        .getKind());
        assertEquals(
                BusinessException.Kind.UNAUTHORIZED,
                assertThrows(
                                BusinessException.class,
                                () -> RolePolicy.requireAny(null, Role.OPERATOR))
                        .getKind());
        var unavailable = BusinessException.unavailable("MQTT_UNAVAILABLE", "broker down");
        assertEquals(BusinessException.Kind.UNAVAILABLE, unavailable.getKind());
        assertEquals("MQTT_UNAVAILABLE", unavailable.getCode());
    }
}
