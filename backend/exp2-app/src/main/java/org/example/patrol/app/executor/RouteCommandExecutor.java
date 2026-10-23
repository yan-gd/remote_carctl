package org.example.patrol.app.executor;

import org.example.patrol.app.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class RouteCommandExecutor {
    private final RouteGateway routes;
    private final UserGateway users;
    private final AuditService audit;
    private final Clock clock;

    public RouteCommandExecutor(
            RouteGateway routes, UserGateway users, AuditService audit, Clock clock) {
        this.routes = routes;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    private User actor(long id) {
        var u = users.userById(id);
        RolePolicy.require(u, Role.OPERATOR);
        return u;
    }

    private PatrolRoute build(Long id, RouteCommand c, java.time.Instant created) {
        return new PatrolRoute(
                id,
                c.name(),
                c.description(),
                c.points().stream()
                        .map(p -> new RoutePoint(p.sequence(), p.x(), p.y(), p.yaw()))
                        .toList(),
                created,
                clock.instant());
    }

    @Transactional
    public RouteView create(RouteCommand c, long actor) {
        var u = actor(actor);
        var r = routes.insertRoute(build(null, c, clock.instant()));
        audit.success(u, "ROUTE_CREATE", "route:" + r.id());
        return Views.route(r);
    }

    @Transactional
    public RouteView update(long id, RouteCommand c, long actor) {
        var u = actor(actor);
        var old = Views.required(routes.route(id), "路线");
        var r = build(id, c, old.createdAt());
        routes.updateRoute(r);
        audit.success(u, "ROUTE_UPDATE", "route:" + id);
        return Views.route(r);
    }

    @Transactional
    public void delete(long id, long actor) {
        var u = actor(actor);
        var r = Views.required(routes.route(id), "路线");
        r.checkDeletable(routes.routeReferenced(id));
        routes.deleteRoute(id);
        audit.success(u, "ROUTE_DELETE", "route:" + id);
    }
}
