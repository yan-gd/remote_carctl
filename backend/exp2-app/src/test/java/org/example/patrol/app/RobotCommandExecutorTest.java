package org.example.patrol.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.example.patrol.app.executor.RobotCommandExecutor;
import org.example.patrol.client.RobotModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Arrays;

class RobotCommandExecutorTest {
    static final String OP = "ROBOT_CONTROL";

    final RobotGateway gateway = mock(RobotGateway.class);
    final RobotMonitor monitor = mock(RobotMonitor.class);
    final UserGateway users = mock(UserGateway.class);
    final AuditService audit = mock(AuditService.class);
    final TestClock clock = new TestClock();
    final RobotSettings settings =
            new RobotSettings(
                    "SCOUT-001",
                    "Scout",
                    "SCOUT MINI",
                    Duration.ofSeconds(3),
                    Duration.ofMillis(1500),
                    null,
                    null);
    final User operator = new User(1L, "op", "", Role.OPERATOR, clock.instant());
    final User monitorUser = new User(2L, "mon", "", Role.MONITOR, clock.instant());
    final User viewer = new User(3L, "view", "", Role.VIEWER, clock.instant());
    RobotCommandExecutor executor;

    @BeforeEach
    void setUp() {
        when(users.userById(1)).thenReturn(operator);
        when(users.userById(2)).thenReturn(monitorUser);
        when(users.userById(3)).thenReturn(viewer);
        when(gateway.connected()).thenReturn(true);
        when(monitor.online()).thenReturn(true);
        when(gateway.publish(any(), any())).thenReturn(41L, 42L, 43L, 44L, 45L);
        executor = new RobotCommandExecutor(gateway, monitor, users, audit, settings, clock);
    }

    static RobotControlCommand cmd(String command) {
        return new RobotControlCommand(command);
    }

    BusinessException rejected(String command, long actor) {
        return assertThrows(BusinessException.class, () -> executor.control(cmd(command), actor));
    }

    @Test
    void operatorCommandIsPublishedAndAudited() {
        var result = executor.control(cmd("FORWARD"), 1);
        assertTrue(result.accepted());
        assertEquals("FORWARD", result.command());
        assertEquals(41, result.seq());
        assertEquals(clock.instant(), result.timestamp());
        verify(gateway).publish(RobotCommand.FORWARD, clock.instant());
        verify(audit).success(operator, OP, "robot:SCOUT-001:FORWARD");
        verify(audit, never()).failure(any(), any(), any(), any(), any());
    }

    @Test
    void keepaliveRepeatsAreNotLoggedButNewEpisodesAre() {
        for (int i = 0; i < 10; i++) {
            executor.control(cmd("FORWARD"), 1);
            clock.advance(200);
        }
        verify(gateway, times(10)).publish(eq(RobotCommand.FORWARD), any());
        verify(audit, times(1)).success(operator, OP, "robot:SCOUT-001:FORWARD");
        executor.control(cmd("STOP"), 1);
        verify(audit).success(operator, OP, "robot:SCOUT-001:STOP");
        clock.advance(1501);
        executor.control(cmd("FORWARD"), 1);
        verify(audit, times(2)).success(operator, OP, "robot:SCOUT-001:FORWARD");
    }

    @Test
    void keepaliveWindowIsPerUser() {
        executor.control(cmd("STOP"), 1);
        executor.control(cmd("STOP"), 2);
        verify(audit).success(operator, OP, "robot:SCOUT-001:STOP");
        verify(audit).success(monitorUser, OP, "robot:SCOUT-001:STOP");
    }

    @Test
    void monitorMayOnlyStop() {
        var e = rejected("FORWARD", 2);
        assertEquals(BusinessException.Kind.FORBIDDEN, e.getKind());
        assertEquals("STOP", executor.control(cmd("STOP"), 2).command());
        verify(gateway, never()).publish(eq(RobotCommand.FORWARD), any());
        verify(audit).failure(2L, "mon", OP, "robot:SCOUT-001:FORWARD", "FORBIDDEN");
    }

    @Test
    void otherRolesAndUnknownUsersNeverPublish() {
        assertEquals(BusinessException.Kind.FORBIDDEN, rejected("STOP", 3).getKind());
        assertEquals(BusinessException.Kind.UNAUTHORIZED, rejected("STOP", 99).getKind());
        verify(gateway, never()).publish(any(), any());
        verify(audit).failure(3L, "view", OP, "robot:SCOUT-001:STOP", "FORBIDDEN");
        verify(audit).failure(null, "anonymous", OP, "robot:SCOUT-001:STOP", "UNAUTHORIZED");
    }

    @Test
    void invalidCommandsAreRejectedWithoutEchoingInput() {
        for (String bad : Arrays.asList(null, "forward", "HOME", "x".repeat(500)))
            assertEquals("INVALID_COMMAND", rejected(bad, 1).getCode());
        assertEquals(
                "INVALID_COMMAND",
                assertThrows(BusinessException.class, () -> executor.control(null, 1)).getCode());
        verify(gateway, never()).publish(any(), any());
        verify(audit, times(1))
                .failure(1L, "op", OP, "robot:SCOUT-001:INVALID", "INVALID_COMMAND");
    }

    @Test
    void mqttDownRejectsEveryCommandWith503() {
        when(gateway.connected()).thenReturn(false);
        assertEquals("MQTT_UNAVAILABLE", rejected("STOP", 1).getCode());
        assertEquals(BusinessException.Kind.UNAVAILABLE, rejected("FORWARD", 1).getKind());
        verify(gateway, never()).publish(any(), any());
    }

    @Test
    void offlineRobotRefusesMotionButStillAcceptsStop() {
        when(monitor.online()).thenReturn(false);
        var e = rejected("LEFT", 1);
        assertEquals("ROBOT_OFFLINE", e.getCode());
        assertEquals(BusinessException.Kind.CONFLICT, e.getKind());
        assertTrue(executor.control(cmd("STOP"), 1).accepted());
        verify(gateway).publish(eq(RobotCommand.STOP), any());
        verify(gateway, never()).publish(eq(RobotCommand.LEFT), any());
    }

    @Test
    void brokerFailureIs503AndAuditedAsFailure() {
        when(gateway.publish(any(), any()))
                .thenThrow(BusinessException.unavailable("MQTT_UNAVAILABLE", "timeout"));
        assertEquals("MQTT_UNAVAILABLE", rejected("BACKWARD", 1).getCode());
        verify(audit, never()).success(any(), any(), any());
        verify(audit).failure(1L, "op", OP, "robot:SCOUT-001:BACKWARD", "MQTT_UNAVAILABLE");
    }

    @Test
    void repeatedFailuresAreLoggedOncePerWindow() {
        when(monitor.online()).thenReturn(false);
        for (int i = 0; i < 5; i++) {
            rejected("FORWARD", 1);
            clock.advance(200);
        }
        verify(audit, times(1)).failure(1L, "op", OP, "robot:SCOUT-001:FORWARD", "ROBOT_OFFLINE");
    }

    @Test
    void auditOutageNeverHidesAnAcceptedCommand() {
        doThrow(new IllegalStateException("database down")).when(audit).success(any(), any(), any());
        assertTrue(executor.control(cmd("RIGHT"), 1).accepted());
        doThrow(new IllegalStateException("database down"))
                .when(audit)
                .failure(any(), any(), any(), any(), any());
        when(monitor.online()).thenReturn(false);
        assertEquals("ROBOT_OFFLINE", rejected("RIGHT", 1).getCode());
    }
}
