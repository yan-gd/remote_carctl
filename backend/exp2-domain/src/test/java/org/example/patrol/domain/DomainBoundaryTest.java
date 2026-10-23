package org.example.patrol.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class DomainBoundaryTest {
    final Instant now = Instant.parse("2026-10-19T00:00:00Z");

    @Test
    void rejectsInvalidRouteBoundaries() {
        for (String name : Arrays.asList(null, "", " ", "x".repeat(101)))
            assertThrows(
                    BusinessException.class,
                    () ->
                            new PatrolRoute(
                                    null,
                                    name,
                                    null,
                                    List.of(new RoutePoint(0, 0, 0, null)),
                                    now,
                                    now));
        assertThrows(
                BusinessException.class,
                () ->
                        new PatrolRoute(
                                null,
                                "route",
                                "x".repeat(1001),
                                List.of(new RoutePoint(0, 0, 0, null)),
                                now,
                                now));
        assertThrows(
                BusinessException.class,
                () -> new PatrolRoute(null, "route", null, null, now, now));
        assertThrows(
                BusinessException.class,
                () ->
                        new PatrolRoute(
                                null, "route", null, Arrays.asList((RoutePoint) null), now, now));
        assertThrows(
                BusinessException.class,
                () ->
                        new PatrolRoute(
                                null,
                                "route",
                                null,
                                Collections.nCopies(201, new RoutePoint(0, 0, 0, null)),
                                now,
                                now));
        var route =
                new PatrolRoute(
                        null, " route ", null, List.of(new RoutePoint(0, 0, 0, null)), now, now);
        assertEquals("route", route.name());
        assertDoesNotThrow(() -> route.checkDeletable(false));
    }

    @Test
    void finiteCoordinatesAndYawAreRequired() {
        assertThrows(BusinessException.class, () -> new RoutePoint(-1, 0, 0, null));
        assertThrows(BusinessException.class, () -> new RoutePoint(0, Double.NaN, 0, null));
        assertThrows(
                BusinessException.class,
                () -> new RoutePoint(0, 0, Double.POSITIVE_INFINITY, null));
        assertThrows(BusinessException.class, () -> new RoutePoint(0, 0, 0, Double.NaN));
        assertThrows(BusinessException.class, () -> new RoutePoint(0, 0, 0, -4.0));
        assertEquals(Math.PI, new RoutePoint(0, 0, 0, Math.PI).yaw());
    }

    @Test
    void pageTimeAndCredentialsHaveStrictBounds() {
        assertThrows(BusinessException.class, () -> Rules.page(1000001, 1));
        assertThrows(BusinessException.class, () -> Rules.page(1, 0));
        assertThrows(BusinessException.class, () -> Rules.page(1, 101));
        assertThrows(BusinessException.class, () -> Rules.page(0, 20));
        assertDoesNotThrow(() -> Rules.page(1, 100));
        assertDoesNotThrow(() -> Rules.range(null, now));
        assertDoesNotThrow(() -> Rules.range(now, null));
        assertDoesNotThrow(() -> Rules.range(now, now));
        assertThrows(BusinessException.class, () -> Rules.range(now, now.minusSeconds(1)));
        for (String user : Arrays.asList(null, "a", "name with space"))
            assertThrows(
                    BusinessException.class, () -> User.validateCredentials(user, "Password123!"));
        for (String password : Arrays.asList(null, "short", "x".repeat(73), "含中文的密码123"))
            assertThrows(BusinessException.class, () -> User.validateCredentials("user", password));
        assertDoesNotThrow(() -> User.validateCredentials("user_123", "Password123!"));
    }

    @Test
    void taskPlanAndCancellationRules() {
        assertThrows(
                BusinessException.class,
                () -> new PatrolTask(null, "task", 0, 1, null, TaskStatus.DRAFT, now, now));
        assertThrows(
                BusinessException.class,
                () -> new PatrolTask(null, "task", 1, 0, null, TaskStatus.DRAFT, now, now));
        assertThrows(
                BusinessException.class,
                () -> new PatrolTask(null, "task", 1, 1, null, null, now, now));
        assertThrows(BusinessException.class, () -> TaskStatus.DRAFT.checkTransition(null));
        var cancelled = new PatrolTask(1L, "task", 1, 1, null, TaskStatus.CANCELLED, now, now);
        assertThrows(
                BusinessException.class,
                () -> cancelled.revise("task", 1, null, TaskStatus.CANCELLED, now));
        assertDoesNotThrow(() -> TaskStatus.CANCELLED.checkTransition(TaskStatus.CANCELLED));
        var alarm =
                new Alarm(
                        1L,
                        "title",
                        null,
                        AlarmLevel.INFO,
                        AlarmStatus.UNACKNOWLEDGED,
                        "TEST",
                        now,
                        null,
                        null);
        assertThrows(BusinessException.class, () -> alarm.acknowledge(0, now));
    }
}
