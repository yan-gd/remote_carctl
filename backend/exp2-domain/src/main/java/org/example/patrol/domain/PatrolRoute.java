package org.example.patrol.domain;

import java.time.Instant;
import java.util.*;

public record PatrolRoute(
        Long id,
        String name,
        String description,
        List<RoutePoint> points,
        Instant createdAt,
        Instant updatedAt) {
    public PatrolRoute {
        name = Rules.name(name, 100);
        if (description != null && description.length() > 1000)
            throw BusinessException.invalid("描述最多1000字符");
        if (points == null
                || points.isEmpty()
                || points.size() > 200
                || points.stream().anyMatch(Objects::isNull))
            throw BusinessException.invalid("路线须包含1-200个点位");
        if (points.stream().map(RoutePoint::sequence).distinct().count() != points.size())
            throw BusinessException.invalid("点位序号不能重复");
        points = points.stream().sorted(Comparator.comparingInt(RoutePoint::sequence)).toList();
    }

    public void checkDeletable(boolean referenced) {
        if (referenced) throw BusinessException.conflict("路线已被任务引用，不能删除");
    }
}
