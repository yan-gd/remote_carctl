package org.example.patrol.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class DomainRulesTest {
    @Test
    void routeSortsAndRejectsDuplicatePoints() {
        var r =
                new PatrolRoute(
                        null,
                        "路线",
                        null,
                        List.of(new RoutePoint(2, 1, 1, null), new RoutePoint(0, 0, 0, null)),
                        Instant.now(),
                        Instant.now());
        assertEquals(0, r.points().get(0).sequence());
        assertThrows(
                BusinessException.class,
                () ->
                        new PatrolRoute(
                                null,
                                "路线",
                                null,
                                List.of(
                                        new RoutePoint(0, 1, 1, null),
                                        new RoutePoint(0, 0, 0, null)),
                                null,
                                null));
        assertThrows(BusinessException.class, () -> r.checkDeletable(true));
    }

    @Test
    void validatesPlansAndCancellation() {
        assertThrows(
                BusinessException.class,
                () -> new PatrolTask(null, "任务", 1, 1, null, TaskStatus.PLANNED, null, null));
        assertThrows(
                BusinessException.class,
                () -> TaskStatus.CANCELLED.checkTransition(TaskStatus.DRAFT));
        assertDoesNotThrow(() -> TaskStatus.DRAFT.checkTransition(TaskStatus.PLANNED));
    }

    @Test
    void confirmationIsOneWay() {
        var a =
                new Alarm(
                                1L,
                                "测试",
                                "夹具",
                                AlarmLevel.WARNING,
                                AlarmStatus.UNACKNOWLEDGED,
                                "TEST_FIXTURE",
                                Instant.now(),
                                null,
                                null)
                        .acknowledge(1, Instant.now());
        assertEquals(1L, a.acknowledgedBy());
        assertThrows(BusinessException.class, () -> a.acknowledge(1, Instant.now()));
    }
}
