package org.example.patrol.app;

import org.example.patrol.client.NavigationModels;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Clock;
import java.util.*;

/** A finite onboard mission, renewed by the backend independently of browser pages. */
@Service
public class NavigationService {
    private final RobotGateway gateway;
    private final RobotMonitor monitor;
    private final UserGateway users;
    private final AuditService audit;
    private final Clock clock;
    private final boolean enabled;
    private String mission;
    private long owner;
    private long leaseAt;

    public NavigationService(RobotGateway gateway, RobotMonitor monitor, UserGateway users,
                             AuditService audit, Clock clock,
                             @Value("${PATROL_NAVIGATION_ENABLED:false}") boolean enabled) {
        this.gateway=gateway; this.monitor=monitor; this.users=users; this.audit=audit; this.clock=clock; this.enabled=enabled;
    }

    public record State(boolean enabled, boolean ready, String reason, NavigationReport robot,
                        String missionId, Long ownerId, boolean active) {}
    public record Accepted(boolean accepted, String missionId, long seq) {}

    public synchronized State status() {
        NavigationReport report = monitor.navigation();
        return new State(enabled, enabled && report != null && report.ready(), !enabled ? "NAVIGATION_DISABLED"
                : report == null ? "NO_LIVE_NAVIGATION_REPORT" : report.reason(), report,
                mission, mission == null ? null : owner, mission != null);
    }

    public synchronized Accepted start(NavigationModels.Start request, long actor) {
        var user = users.userById(actor);
        RolePolicy.require(user, Permission.NAVIGATE);
        if (!enabled) throw BusinessException.unavailable("NAVIGATION_DISABLED", "请先完成地图、定位、避障检查再启用导航");
        if (!gateway.connected()) throw BusinessException.unavailable("MQTT_UNAVAILABLE", "MQTT不可用");
        var state = status();
        if (!state.ready()) throw BusinessException.conflict("导航未就绪：" + state.reason());
        if (request == null || request.points() == null || request.points().isEmpty() || request.points().size() > 30
                || request.cycles() < 1 || request.cycles() > 20) throw BusinessException.invalid("点位1-30个，轮次1-20");
        if (!Objects.equals(request.mapId(), state.robot().mapId())) throw BusinessException.conflict("导航参考已变化，请刷新目标点");
        boolean relative = "relative".equals(state.robot().mode());
        if (relative && (request.points().size() != 1 || request.cycles() != 1))
            throw BusinessException.invalid("相对导航仅支持一个目标、执行一次");
        if (mission != null || Set.of("ACTIVE", "PENDING").contains(state.robot().state()))
            throw BusinessException.conflict("已有导航任务，请先取消");
        var points = request.points().stream().map(p -> {
            if (p == null || p.x() == null || p.y() == null || p.yaw() == null) throw BusinessException.invalid("点位不能为空");
            var point = new NavigationOrder.Point(p.x(), p.y(), p.yaw());
            if (relative && (Math.hypot(p.x(), p.y()) < 0.1 || Math.hypot(p.x(), p.y()) > 3))
                throw BusinessException.invalid("相对目标距离必须在0.1至3米内");
            return point;
        }).toList();
        String next = UUID.randomUUID().toString();
        long seq = gateway.publishNavigation(new NavigationOrder("START", next, request.mapId(), points, request.cycles(), state.robot().mode()), clock.instant());
        mission=next; owner=actor; leaseAt=clock.millis();
        record(() -> audit.success(user, "NAVIGATION_START", "mission:" + next));
        return new Accepted(true, next, seq);
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void renewLease() {
        if (mission == null) return;
        var report = monitor.navigation();
        if (report != null && Objects.equals(mission, report.missionId())
                && Set.of("SUCCEEDED", "CANCELLED", "ABORTED", "REJECTED").contains(report.state())) {
            finish(report.state());
            return;
        }
        // Do not revive an expired mission after a long JVM pause or transport outage.
        if (clock.millis() - leaseAt > 3000) {
            stopBestEffort();
            finish("LEASE_EXPIRED");
            return;
        }
        if (!gateway.connected() || report == null || !report.ready()
                || !Objects.equals(mission, report.missionId())
                || !Set.of("ACTIVE", "PENDING").contains(report.state())) return;
        try {
            var actor = users.userById(owner);
            if (actor == null || !actor.can(Permission.NAVIGATE)) {
                stopBestEffort();
                finish("PERMISSION_REVOKED");
                return;
            }
            gateway.publishNavigation(new NavigationOrder("KEEPALIVE", mission, null, List.of(), 1), clock.instant());
            leaseAt = clock.millis();
        } catch (RuntimeException e) {
            // No successful renewal: the robot's own 3s lease closes the velocity gate.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Navigation lease renewal unavailable");
        }
    }

    public synchronized Accepted cancel(long actor) {
        var user=users.userById(actor);
        RolePolicy.require(user, Permission.STOP);
        // Publish the established STOP first, even if the optional navigation protocol fails.
        long seq=gateway.publish(RobotCommand.STOP, clock.instant());
        String target=mission; mission=null;
        gateway.publishNavigation(new NavigationOrder("CANCEL", target, null, List.of(), 1),clock.instant());
        record(() -> audit.success(user,"NAVIGATION_CANCEL","mission:"+target));
        return new Accepted(true,target,seq);
    }

    private void finish(String result) {
        String target = mission;
        long actorId = owner;
        mission = null;
        // This is a navigation session result, never a fabricated patrol_task completion.
        record(() -> audit.success(users.userById(actorId), "NAVIGATION_END", "mission:" + target + ":" + result));
    }

    private void stopBestEffort() {
        try { gateway.publish(RobotCommand.STOP, clock.instant()); }
        catch (RuntimeException e) { /* The independent onboard lease still expires. */ }
    }

    private void record(Runnable operation) {
        try { operation.run(); } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Navigation audit unavailable");
        }
    }
}
