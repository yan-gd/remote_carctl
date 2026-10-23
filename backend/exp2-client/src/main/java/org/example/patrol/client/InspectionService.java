package org.example.patrol.client;

import org.example.patrol.client.ApiModels.*;

public interface InspectionService {
    UserView register(AuthCommand cmd);

    TokenView login(AuthCommand cmd);

    UserView me(long actor);

    RouteView createRoute(RouteCommand cmd, long actor);

    RouteView updateRoute(long id, RouteCommand cmd, long actor);

    void deleteRoute(long id, long actor);

    RouteView route(long id);

    PageView<RouteView> routes(Query query);

    TaskView createTask(TaskCommand cmd, long actor);

    TaskView updateTask(long id, TaskCommand cmd, long actor);

    void deleteTask(long id, long actor);

    TaskView task(long id);

    PageView<TaskView> tasks(Query query);

    AlarmView alarm(long id);

    PageView<AlarmView> alarms(Query query);

    AlarmView acknowledge(long id, long actor);

    PageView<LogView> logs(Query query, long actor);
}
