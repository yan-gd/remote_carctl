package org.example.patrol.infrastructure.gateway;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.example.patrol.infrastructure.data.*;
import org.example.patrol.infrastructure.mapper.PatrolMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class SqlitePatrolGateway
        implements UserGateway, RouteGateway, TaskGateway, AlarmGateway, LogGateway {
    private final PatrolMapper mapper;

    public SqlitePatrolGateway(PatrolMapper mapper) {
        this.mapper = mapper;
    }

    private static Long millis(Instant t) {
        return t == null ? null : t.toEpochMilli();
    }

    private static Instant instant(Long t) {
        return t == null ? null : Instant.ofEpochMilli(t);
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static long offset(int p, int s) {
        Rules.page(p, s);
        return (long) (p - 1) * s;
    }

    public User userById(long id) {
        var user = model(mapper.userById(id));
        return user != null && user.active() ? user : null;
    }

    public User userByName(String name) {
        var user = model(mapper.userByName(name));
        return user != null && user.active() ? user : null;
    }

    public User userByNameIncludingDisabled(String name) { return model(mapper.userByName(name)); }

    public User userIncludingDisabled(long id) { return model(mapper.userById(id)); }

    public List<User> allUsers() { return mapper.allUsers().stream().map(this::model).toList(); }

    public void setAccess(long id, Set<Permission> permissions, boolean admin, boolean disabled) {
        String value = permissions.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        mapper.setAccess(id, value, admin ? 1 : 0, disabled ? 1 : 0);
    }

    public void updateCredentials(long id, String passwordHash, Role role) {
        check(mapper.updateCredentials(id, passwordHash, role.name()));
    }

    public User insertUser(User u) {
        var d = data(u);
        mapper.insertUser(d);
        return model(d);
    }

    public PatrolRoute route(long id) {
        return model(mapper.route(id));
    }

    @Transactional
    public PatrolRoute insertRoute(PatrolRoute r) {
        var d = data(r);
        mapper.insertRoute(d);
        savePoints(d.getId(), r);
        return route(d.getId());
    }

    @Transactional
    public void updateRoute(PatrolRoute r) {
        check(mapper.updateRoute(data(r)));
        mapper.deletePoints(r.id());
        savePoints(r.id(), r);
    }

    private void savePoints(long id, PatrolRoute r) {
        for (var p : r.points()) {
            var d = new PointDO();
            d.setRouteId(id);
            d.setSequence(p.sequence());
            d.setX(p.x());
            d.setY(p.y());
            d.setYaw(p.yaw());
            mapper.insertPoint(d);
        }
    }

    public void deleteRoute(long id) {
        check(mapper.deleteRoute(id));
    }

    public boolean routeReferenced(long id) {
        return mapper.routeReferences(id) > 0;
    }

    public Page<PatrolRoute> routes(int p, int s) {
        return new Page<>(
                mapper.routes(s, offset(p, s)).stream().map(this::model).toList(),
                mapper.routeCount(),
                p,
                s);
    }

    public PatrolTask task(long id) {
        return model(mapper.task(id));
    }

    public PatrolTask insertTask(PatrolTask t) {
        var d = data(t);
        mapper.insertTask(d);
        return model(d);
    }

    public void updateTask(PatrolTask t) {
        check(mapper.updateTask(data(t)));
    }

    public void deleteTask(long id) {
        check(mapper.deleteTask(id));
    }

    public Page<PatrolTask> tasks(int p, int s, TaskStatus status) {
        return new Page<>(
                mapper.tasks(s, offset(p, s), name(status)).stream().map(this::model).toList(),
                mapper.taskCount(name(status)),
                p,
                s);
    }

    public Alarm alarm(long id) {
        return model(mapper.alarm(id));
    }

    public void acknowledge(Alarm a) {
        if (mapper.acknowledge(data(a)) != 1) throw BusinessException.conflict("告警已被其他用户确认");
    }

    public Page<Alarm> alarms(
            int p, int s, AlarmLevel level, AlarmStatus status, Instant from, Instant to, String keyword) {
        Rules.range(from, to);
        return new Page<>(
                mapper
                        .alarms(
                                s,
                                offset(p, s),
                                name(level),
                                name(status),
                                millis(from),
                                millis(to), keyword)
                        .stream()
                        .map(this::model)
                        .toList(),
                mapper.alarmCount(name(level), name(status), millis(from), millis(to), keyword),
                p,
                s);
    }

    public void seedDemoAlarm() {
        mapper.seedDemoAlarm(System.currentTimeMillis());
    }

    public Alarm insertAlarm(Alarm a) {
        var d = data(a);
        mapper.insertAlarm(d);
        return model(d);
    }

    public void appendLog(ControlLog l) {
        mapper.appendLog(data(l));
    }

    public Page<ControlLog> logs(int p, int s, String op, Instant from, Instant to, Long actorId, String keyword) {
        Rules.range(from, to);
        return new Page<>(
                mapper.logs(actorId, s, offset(p, s), op, millis(from), millis(to), keyword).stream()
                        .map(this::model)
                        .toList(),
                mapper.logCount(actorId, op, millis(from), millis(to), keyword),
                p,
                s);
    }

    private static void check(int rows) {
        if (rows != 1) throw BusinessException.missing("资源");
    }

    private User model(UserDO d) {
        if (d == null) return null;
        Role role = Role.valueOf(d.getRole());
        UserAccessDO access = mapper.userAccess(d.getId());
        Set<Permission> permissions = Permission.defaults(role);
        if (access != null) {
            permissions = EnumSet.noneOf(Permission.class);
            if (!access.getPermissions().isBlank())
                for (String value : access.getPermissions().split(",")) permissions.add(Permission.valueOf(value));
        }
        return new User(
                d.getId(),
                d.getUsername(),
                d.getPasswordHash(),
                role,
                instant(d.getCreatedAt()),
                access != null && access.isAdmin(), permissions,
                access == null || !access.isDisabled());
    }

    private UserDO data(User m) {
        var d = new UserDO();
        d.setId(m.id());
        d.setUsername(m.username());
        d.setPasswordHash(m.passwordHash());
        d.setRole(m.role().name());
        d.setCreatedAt(millis(m.createdAt()));
        return d;
    }

    private PatrolRoute model(RouteDO d) {
        if (d == null) return null;
        return new PatrolRoute(
                d.getId(),
                d.getName(),
                d.getDescription(),
                mapper.points(d.getId()).stream()
                        .map(p -> new RoutePoint(p.getSequence(), p.getX(), p.getY(), p.getYaw()))
                        .toList(),
                instant(d.getCreatedAt()),
                instant(d.getUpdatedAt()));
    }

    private RouteDO data(PatrolRoute m) {
        var d = new RouteDO();
        d.setId(m.id());
        d.setName(m.name());
        d.setDescription(m.description());
        d.setCreatedAt(millis(m.createdAt()));
        d.setUpdatedAt(millis(m.updatedAt()));
        return d;
    }

    private PatrolTask model(TaskDO d) {
        if (d == null) return null;
        return new PatrolTask(
                d.getId(),
                d.getName(),
                d.getRouteId(),
                d.getCreatorId(),
                instant(d.getPlannedAt()),
                TaskStatus.valueOf(d.getStatus()),
                instant(d.getCreatedAt()),
                instant(d.getUpdatedAt()));
    }

    private TaskDO data(PatrolTask m) {
        var d = new TaskDO();
        d.setId(m.id());
        d.setName(m.name());
        d.setRouteId(m.routeId());
        d.setCreatorId(m.creatorId());
        d.setPlannedAt(millis(m.plannedAt()));
        d.setStatus(m.status().name());
        d.setCreatedAt(millis(m.createdAt()));
        d.setUpdatedAt(millis(m.updatedAt()));
        return d;
    }

    private Alarm model(AlarmDO d) {
        if (d == null) return null;
        return new Alarm(
                d.getId(),
                d.getTitle(),
                d.getDescription(),
                AlarmLevel.valueOf(d.getLevel()),
                AlarmStatus.valueOf(d.getStatus()),
                d.getSource(),
                instant(d.getOccurredAt()),
                d.getAcknowledgedBy(),
                instant(d.getAcknowledgedAt()));
    }

    private AlarmDO data(Alarm m) {
        var d = new AlarmDO();
        d.setId(m.id());
        d.setTitle(m.title());
        d.setDescription(m.description());
        d.setLevel(m.level().name());
        d.setStatus(m.status().name());
        d.setSource(m.source());
        d.setOccurredAt(millis(m.occurredAt()));
        d.setAcknowledgedBy(m.acknowledgedBy());
        d.setAcknowledgedAt(millis(m.acknowledgedAt()));
        return d;
    }

    private ControlLog model(LogDO d) {
        if (d == null) return null;
        return new ControlLog(
                d.getId(),
                d.getActorId(),
                d.getActorName(),
                d.getOperation(),
                d.getTarget(),
                d.getResult(),
                instant(d.getOccurredAt()),
                d.getError());
    }

    private LogDO data(ControlLog m) {
        var d = new LogDO();
        d.setId(m.id());
        d.setActorId(m.actorId());
        d.setActorName(m.actorName());
        d.setOperation(m.operation());
        d.setTarget(m.target());
        d.setResult(m.result());
        d.setOccurredAt(millis(m.occurredAt()));
        d.setError(m.error());
        return d;
    }
}
