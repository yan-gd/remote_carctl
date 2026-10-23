package org.example.patrol.app;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.example.patrol.app.executor.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

class ExecutorTest {
    final UserGateway users = mock(UserGateway.class);
    final RouteGateway routes = mock(RouteGateway.class);
    final TaskGateway tasks = mock(TaskGateway.class);
    final AuditService audit = mock(AuditService.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void forbiddenCommandDoesNotWrite() {
        when(users.userById(1))
                .thenReturn(new User(1L, "viewer", "", Role.VIEWER, clock.instant()));
        var executor = new RouteCommandExecutor(routes, users, audit, clock);
        assertEquals(
                BusinessException.Kind.FORBIDDEN,
                assertThrows(BusinessException.class, () -> executor.delete(2, 1)).getKind());
        verifyNoInteractions(routes, audit);
    }

    @Test
    void missingUserDoesNotWrite() {
        var executor = new RouteCommandExecutor(routes, users, audit, clock);
        assertEquals(
                BusinessException.Kind.UNAUTHORIZED,
                assertThrows(BusinessException.class, () -> executor.delete(2, 1)).getKind());
        verifyNoInteractions(routes, audit);
    }

    @Test
    void missingRouteStopsTaskBeforeInsert() {
        when(users.userById(1))
                .thenReturn(new User(1L, "operator", "", Role.OPERATOR, clock.instant()));
        var executor = new TaskCommandExecutor(tasks, routes, users, audit, clock);
        assertEquals(
                BusinessException.Kind.NOT_FOUND,
                assertThrows(
                                BusinessException.class,
                                () ->
                                        executor.create(
                                                new TaskCommand("task", 99L, null, "DRAFT"), 1))
                        .getKind());
        verifyNoInteractions(tasks, audit);
    }

    @Test
    void routeSuccessLogsPersistedIdentifier() {
        var user = new User(1L, "operator", "", Role.OPERATOR, clock.instant());
        when(users.userById(1)).thenReturn(user);
        var saved =
                new PatrolRoute(
                        2L,
                        "route",
                        null,
                        List.of(new RoutePoint(0, 0, 0, null)),
                        clock.instant(),
                        clock.instant());
        when(routes.insertRoute(any())).thenReturn(saved);
        var result =
                new RouteCommandExecutor(routes, users, audit, clock)
                        .create(
                                new RouteCommand(
                                        "route",
                                        null,
                                        List.of(new PointCommand(0, 0.0, 0.0, null))),
                                1);
        assertEquals(2L, result.id());
        verify(audit).success(user, "ROUTE_CREATE", "route:2");
    }

    @Test
    void gatewayFailureDoesNotRecordSuccess() {
        when(users.userById(1))
                .thenReturn(new User(1L, "operator", "", Role.OPERATOR, clock.instant()));
        when(routes.insertRoute(any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        var c = new RouteCommand("route", null, List.of(new PointCommand(0, 0.0, 0.0, null)));
        assertThrows(
                IllegalStateException.class,
                () -> new RouteCommandExecutor(routes, users, audit, clock).create(c, 1));
        verifyNoInteractions(audit);
    }
}
