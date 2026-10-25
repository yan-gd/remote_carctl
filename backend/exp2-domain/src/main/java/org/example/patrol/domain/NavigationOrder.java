package org.example.patrol.domain;

import java.util.List;

/** Coordinates follow mode: saved map, or a single frozen body-relative offset. */
public record NavigationOrder(String action, String missionId, String mapId,
                              List<Point> points, int cycles, String mode) {
    public NavigationOrder(String action, String missionId, String mapId, List<Point> points, int cycles) {
        this(action, missionId, mapId, points, cycles, "map");
    }
    public record Point(double x, double y, double yaw) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(yaw)
                    || Math.abs(x) > 10000 || Math.abs(y) > 10000 || Math.abs(yaw) > Math.PI)
                throw BusinessException.invalid("目标坐标必须为有限值，范围±10000m，yaw范围±π");
        }
    }
}
