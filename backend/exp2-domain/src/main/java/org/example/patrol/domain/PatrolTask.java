package org.example.patrol.domain;

import java.time.Instant;

public record PatrolTask(
        Long id,
        String name,
        long routeId,
        long creatorId,
        Instant plannedAt,
        TaskStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public PatrolTask {
        name = Rules.name(name, 100);
        if (routeId <= 0 || creatorId <= 0 || status == null)
            throw BusinessException.invalid("任务路线、创建人或状态非法");
        if (status == TaskStatus.PLANNED && plannedAt == null)
            throw BusinessException.invalid("已计划任务必须提供计划时间");
    }

    public PatrolTask revise(
            String name, long routeId, Instant plannedAt, TaskStatus next, Instant now) {
        status.checkTransition(next);
        if (status == TaskStatus.CANCELLED) throw BusinessException.conflict("已取消任务不可修改，可删除后重新创建");
        return new PatrolTask(id, name, routeId, creatorId, plannedAt, next, createdAt, now);
    }
}
