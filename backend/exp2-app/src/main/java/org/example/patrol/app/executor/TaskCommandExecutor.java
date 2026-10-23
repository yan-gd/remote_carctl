package org.example.patrol.app.executor;

import org.example.patrol.app.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class TaskCommandExecutor {
    private final TaskGateway tasks;
    private final RouteGateway routes;
    private final UserGateway users;
    private final AuditService audit;
    private final Clock clock;

    public TaskCommandExecutor(
            TaskGateway tasks,
            RouteGateway routes,
            UserGateway users,
            AuditService audit,
            Clock clock) {
        this.tasks = tasks;
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

    @Transactional
    public TaskView create(TaskCommand c, long actor) {
        var u = actor(actor);
        Views.required(routes.route(c.routeId()), "关联路线");
        var status = Views.parse(TaskStatus.class, c.status());
        if (status == TaskStatus.CANCELLED) throw BusinessException.invalid("新任务不能直接为已取消");
        var t =
                tasks.insertTask(
                        new PatrolTask(
                                null,
                                c.name(),
                                c.routeId(),
                                actor,
                                c.plannedAt(),
                                status,
                                clock.instant(),
                                clock.instant()));
        audit.success(u, "TASK_CREATE", "task:" + t.id());
        return Views.task(t);
    }

    @Transactional
    public TaskView update(long id, TaskCommand c, long actor) {
        var u = actor(actor);
        var old = Views.required(tasks.task(id), "任务");
        Views.required(routes.route(c.routeId()), "关联路线");
        var t =
                old.revise(
                        c.name(),
                        c.routeId(),
                        c.plannedAt(),
                        Views.parse(TaskStatus.class, c.status()),
                        clock.instant());
        tasks.updateTask(t);
        audit.success(u, "TASK_UPDATE", "task:" + id);
        return Views.task(t);
    }

    @Transactional
    public void delete(long id, long actor) {
        var u = actor(actor);
        Views.required(tasks.task(id), "任务");
        tasks.deleteTask(id);
        audit.success(u, "TASK_DELETE", "task:" + id);
    }
}
