package org.example.patrol.app;

import org.example.patrol.client.ApiModels.*;
import org.example.patrol.domain.*;

import java.util.function.Function;

public final class Views {
    private Views() {}

    public static UserView user(User u) {
        return new UserView(u.id(), u.username(), u.role().name(), u.createdAt());
    }

    public static RouteView route(PatrolRoute r) {
        return new RouteView(
                r.id(),
                r.name(),
                r.description(),
                r.points().stream()
                        .map(p -> new PointView(p.sequence(), p.x(), p.y(), p.yaw()))
                        .toList(),
                r.createdAt(),
                r.updatedAt());
    }

    public static TaskView task(PatrolTask t) {
        return new TaskView(
                t.id(),
                t.name(),
                t.routeId(),
                t.creatorId(),
                t.plannedAt(),
                t.status().name(),
                t.createdAt(),
                t.updatedAt());
    }

    public static AlarmView alarm(Alarm a) {
        return new AlarmView(
                a.id(),
                a.title(),
                a.description(),
                a.level().name(),
                a.status().name(),
                a.source(),
                a.occurredAt(),
                a.acknowledgedBy(),
                a.acknowledgedAt());
    }

    public static LogView log(ControlLog l) {
        return new LogView(
                l.id(),
                l.actorId(),
                l.actorName(),
                l.operation(),
                l.target(),
                l.result(),
                l.occurredAt(),
                l.error());
    }

    public static <S, T> PageView<T> page(Page<S> p, Function<S, T> f) {
        return new PageView<>(p.items().stream().map(f).toList(), p.total(), p.page(), p.size());
    }

    public static <T> T required(T value, String resource) {
        if (value == null) throw BusinessException.missing(resource);
        return value;
    }

    public static <T extends Enum<T>> T parse(Class<T> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalid("不支持的枚举值：" + type.getSimpleName());
        }
    }

    public static void query(Query q) {
        Rules.page(q.page(), q.size());
        Rules.range(q.from(), q.to());
    }
}
