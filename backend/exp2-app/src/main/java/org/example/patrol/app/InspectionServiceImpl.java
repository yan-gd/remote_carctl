package org.example.patrol.app;

import org.example.patrol.app.executor.*;
import org.example.patrol.client.*;
import org.example.patrol.client.ApiModels.*;
import org.springframework.stereotype.Service;

@Service
public class InspectionServiceImpl implements InspectionService {
    private final AuthCommandExecutor auth;
    private final RouteCommandExecutor routes;
    private final TaskCommandExecutor tasks;
    private final AlarmCommandExecutor alarms;
    private final PatrolQueryExecutor queries;

    public InspectionServiceImpl(
            AuthCommandExecutor auth,
            RouteCommandExecutor routes,
            TaskCommandExecutor tasks,
            AlarmCommandExecutor alarms,
            PatrolQueryExecutor queries) {
        this.auth = auth;
        this.routes = routes;
        this.tasks = tasks;
        this.alarms = alarms;
        this.queries = queries;
    }

    public UserView register(AuthCommand c) {
        return auth.register(c);
    }

    public TokenView login(AuthCommand c) {
        return auth.login(c);
    }

    public UserView me(long id) {
        return queries.me(id);
    }

    public RouteView createRoute(RouteCommand c, long actor) {
        return routes.create(c, actor);
    }

    public RouteView updateRoute(long id, RouteCommand c, long actor) {
        return routes.update(id, c, actor);
    }

    public void deleteRoute(long id, long actor) {
        routes.delete(id, actor);
    }

    public RouteView route(long id) {
        return queries.route(id);
    }

    public PageView<RouteView> routes(Query q) {
        return queries.routes(q);
    }

    public TaskView createTask(TaskCommand c, long actor) {
        return tasks.create(c, actor);
    }

    public TaskView updateTask(long id, TaskCommand c, long actor) {
        return tasks.update(id, c, actor);
    }

    public void deleteTask(long id, long actor) {
        tasks.delete(id, actor);
    }

    public TaskView task(long id) {
        return queries.task(id);
    }

    public PageView<TaskView> tasks(Query q) {
        return queries.tasks(q);
    }

    public AlarmView alarm(long id) {
        return queries.alarm(id);
    }

    public PageView<AlarmView> alarms(Query q) {
        return queries.alarms(q);
    }

    public AlarmView acknowledge(long id, long actor) {
        return alarms.acknowledge(id, actor);
    }

    public PageView<LogView> logs(Query q, long actor) {
        return queries.logs(q, actor);
    }
}
