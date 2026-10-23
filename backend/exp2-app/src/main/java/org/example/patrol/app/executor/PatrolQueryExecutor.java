package org.example.patrol.app.executor;

import org.example.patrol.app.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Component;

@Component
public class PatrolQueryExecutor {
    private final UserGateway users;
    private final RouteGateway routes;
    private final TaskGateway tasks;
    private final AlarmGateway alarms;
    private final LogGateway logs;

    public PatrolQueryExecutor(
            UserGateway users,
            RouteGateway routes,
            TaskGateway tasks,
            AlarmGateway alarms,
            LogGateway logs) {
        this.users = users;
        this.routes = routes;
        this.tasks = tasks;
        this.alarms = alarms;
        this.logs = logs;
    }

    public UserView me(long id) {
        return Views.user(Views.required(users.userById(id), "用户"));
    }

    public RouteView route(long id) {
        return Views.route(Views.required(routes.route(id), "路线"));
    }

    public TaskView task(long id) {
        return Views.task(Views.required(tasks.task(id), "任务"));
    }

    public AlarmView alarm(long id) {
        return Views.alarm(Views.required(alarms.alarm(id), "告警"));
    }

    public PageView<RouteView> routes(Query q) {
        Views.query(q);
        return Views.page(routes.routes(q.page(), q.size()), Views::route);
    }

    public PageView<TaskView> tasks(Query q) {
        Views.query(q);
        return Views.page(
                tasks.tasks(q.page(), q.size(), Views.parse(TaskStatus.class, q.status())),
                Views::task);
    }

    public PageView<AlarmView> alarms(Query q) {
        Views.query(q);
        return Views.page(
                alarms.alarms(
                        q.page(),
                        q.size(),
                        Views.parse(AlarmLevel.class, q.level()),
                        Views.parse(AlarmStatus.class, q.status()),
                        q.from(),
                        q.to()),
                Views::alarm);
    }

    public PageView<LogView> logs(Query q, long actor) {
        Views.query(q);
        var user = Views.required(users.userById(actor), "用户");
        Long scope = (user.role() == Role.OPERATOR || user.role() == Role.MONITOR) ? actor : null;
        if (q.operation() != null && !q.operation().matches("[A-Z_]{1,40}"))
            throw BusinessException.invalid("操作类型须为1-40位大写字母或下划线");
        return Views.page(
                logs.logs(q.page(), q.size(), q.operation(), q.from(), q.to(), scope), Views::log);
    }
}
