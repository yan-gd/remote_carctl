package org.example.patrol.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

class RobotMonitorTest {
    final RobotGateway gateway = mock(RobotGateway.class);
    final AlarmGateway alarms = mock(AlarmGateway.class);
    final TestClock clock = new TestClock();
    final RobotSettings settings =
            new RobotSettings(
                    "SCOUT-001",
                    "Scout",
                    "SCOUT MINI",
                    Duration.ofMillis(3000),
                    Duration.ofMillis(1500),
                    20.0,
                    22.5);
    RobotMonitor monitor;

    @BeforeEach
    void setUp() {
        when(gateway.connected()).thenReturn(true);
        monitor = new RobotMonitor(gateway, alarms, settings, clock);
    }

    List<Alarm> raised() {
        var captor = ArgumentCaptor.forClass(Alarm.class);
        verify(alarms, atLeast(0)).insertAlarm(captor.capture());
        return captor.getAllValues();
    }

    void beat() {
        monitor.onHeartbeat(new RobotHeartbeat(true, true, null));
    }

    RobotTelemetry battery(Double pct, Double volts) {
        return new RobotTelemetry(MotionState.STOPPED, pct, volts, 0.0, 0.0, null, null, null);
    }

    @Test
    void registersItselfAndStartsOffline() {
        verify(gateway).listen(monitor);
        assertFalse(monitor.online());
        var s = monitor.snapshot();
        assertFalse(s.online());
        assertNull(s.lastSeenAt());
        assertNull(s.telemetry());
        assertNull(s.lastAck());
        monitor.evaluate();
        assertTrue(raised().isEmpty());
    }

    @Test
    void heartbeatTimeoutRaisesOneCriticalAlarm() {
        beat();
        monitor.evaluate();
        assertTrue(monitor.online());
        clock.advance(3000);
        assertTrue(monitor.online(), "the timeout boundary is inclusive");
        clock.advance(1);
        assertFalse(monitor.online());
        monitor.evaluate();
        monitor.evaluate();
        clock.advance(10_000);
        monitor.evaluate();
        var alarms = raised();
        assertEquals(1, alarms.size());
        assertEquals(AlarmLevel.CRITICAL, alarms.get(0).level());
        assertEquals("SCOUT-001", alarms.get(0).source());
        assertTrue(alarms.get(0).description().contains("3000"));
        assertEquals(AlarmStatus.UNACKNOWLEDGED, alarms.get(0).status());
    }

    @Test
    void willMessageTakesTheRobotOfflineAtOnce() {
        beat();
        monitor.onHeartbeat(new RobotHeartbeat(false, null, "LWT"));
        assertFalse(monitor.online());
        assertEquals(1, raised().size());
        assertTrue(raised().get(0).description().contains("遗嘱"));
        beat();
        assertTrue(monitor.online());
        assertEquals(1, raised().size(), "coming back online is not an alarm");
    }

    @Test
    void cleanShutdownIsOnlyAWarning() {
        beat();
        monitor.onHeartbeat(new RobotHeartbeat(false, null, "SHUTDOWN"));
        assertEquals(AlarmLevel.WARNING, raised().get(0).level());
        beat();
        monitor.onHeartbeat(new RobotHeartbeat(false, null, null));
        assertTrue(raised().get(1).description().contains("未说明原因"));
    }

    @Test
    void lastSeenIsNotMovedByAnOfflineMessage() {
        beat();
        var seen = monitor.snapshot().lastSeenAt();
        clock.advance(500);
        monitor.onHeartbeat(new RobotHeartbeat(false, null, "LWT"));
        assertEquals(seen, monitor.snapshot().lastSeenAt());
    }

    @Test
    void linkLossRaisesOneMqttAlarmAndNoHeartbeatAlarm() {
        beat();
        monitor.evaluate();
        when(gateway.connected()).thenReturn(false);
        monitor.onLink(false);
        monitor.onLink(false);
        monitor.evaluate();
        assertEquals(1, raised().size());
        assertEquals("MQTT", raised().get(0).source());
        assertFalse(monitor.online());

        when(gateway.connected()).thenReturn(true);
        monitor.onLink(true);
        assertFalse(monitor.online(), "a heartbeat from before the outage does not count");
        beat();
        assertTrue(monitor.online());

        when(gateway.connected()).thenReturn(false);
        monitor.onLink(false);
        assertEquals(2, raised().size(), "a second outage is a new episode");
    }

    @Test
    void failedFirstConnectionIsReportedOnce() {
        when(gateway.connected()).thenReturn(false);
        monitor.onLink(false);
        monitor.onLink(false);
        assertEquals(1, raised().size());
        assertEquals(AlarmLevel.CRITICAL, raised().get(0).level());
    }

    @Test
    void telemetryIsOnlyShownWhileOnlineAndFresh() {
        beat();
        monitor.onStatus(
                new RobotTelemetry(MotionState.FORWARD, null, 25.1, 0.3, 0.0, 1.0, 2.0, 0.5));
        var s = monitor.snapshot();
        assertEquals(MotionState.FORWARD, s.telemetry().motionState());
        assertEquals(Boolean.TRUE, s.chassisConnected());
        assertEquals(clock.instant(), s.telemetryAt());
        clock.advance(2000);
        beat();
        clock.advance(1500);
        assertTrue(monitor.online());
        assertNull(monitor.snapshot().telemetry(), "status older than the timeout is stale");
        clock.advance(5000);
        assertNull(monitor.snapshot().telemetry());
        assertNull(monitor.snapshot().chassisConnected());
        assertNotNull(monitor.snapshot().telemetryAt());
    }

    @Test
    void lowBatteryVoltageAlarmsOnceWithHysteresis() {
        monitor.onStatus(battery(null, 24.0));
        monitor.onStatus(battery(null, 22.4));
        monitor.onStatus(battery(null, 22.6));
        monitor.onStatus(battery(null, 22.4));
        assertEquals(1, raised().size());
        assertTrue(raised().get(0).description().contains("22.40V"));
        monitor.onStatus(battery(null, 22.9));
        monitor.onStatus(battery(null, 22.0));
        assertEquals(2, raised().size());
        monitor.onStatus(battery(null, null));
        assertEquals(2, raised().size(), "missing values never raise or clear the alarm");
    }

    @Test
    void percentageWinsOverVoltage() {
        monitor.onStatus(battery(15.0, 26.0));
        assertEquals(1, raised().size());
        assertTrue(raised().get(0).description().contains("15%"));
        assertEquals(AlarmLevel.WARNING, raised().get(0).level());
    }

    @Test
    void batteryAlarmsCanBeDisabled() {
        var quiet =
                new RobotMonitor(
                        gateway,
                        alarms,
                        new RobotSettings(
                                "SCOUT-001",
                                "Scout",
                                "SCOUT MINI",
                                Duration.ofSeconds(3),
                                Duration.ZERO,
                                null,
                                null),
                        clock);
        quiet.onStatus(battery(1.0, 1.0));
        assertTrue(raised().isEmpty());
    }

    @Test
    void silentChassisWarnsOncePerEpisode() {
        beat();
        monitor.onHeartbeat(new RobotHeartbeat(true, false, null));
        monitor.onHeartbeat(new RobotHeartbeat(true, false, null));
        assertEquals(1, raised().size());
        assertEquals(AlarmLevel.WARNING, raised().get(0).level());
        beat();
        monitor.onHeartbeat(new RobotHeartbeat(true, false, null));
        assertEquals(2, raised().size());
        monitor.onHeartbeat(new RobotHeartbeat(true, null, null));
        assertEquals(2, raised().size(), "unknown is not the same as disconnected");
    }

    @Test
    void alarmStoreOutageDoesNotStopMonitoring() {
        when(alarms.insertAlarm(any())).thenThrow(new IllegalStateException("database down"));
        beat();
        monitor.onHeartbeat(new RobotHeartbeat(false, null, "LWT"));
        assertFalse(monitor.online());
        beat();
        assertTrue(monitor.online());
    }

    @Test
    void receiptsAreKept() {
        monitor.onAck(new RobotAck(7, "FORWARD", "APPLIED", null));
        assertEquals(7, monitor.snapshot().lastAck().seq());
        assertEquals(clock.instant(), monitor.snapshot().lastAckAt());
    }

    @Test
    void settingsRejectUnsafeValues() {
        for (String id : new String[] {null, "", "a/b", "robot#", "x".repeat(65)})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new RobotSettings(
                                    id, "n", "m", Duration.ofSeconds(1), Duration.ZERO, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RobotSettings("R", "n", "m", Duration.ZERO, Duration.ZERO, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RobotSettings(
                                "R", "n", "m", Duration.ofSeconds(1), Duration.ofMillis(-1), null, null));
    }
}
