package org.example.patrol.domain;

import java.util.List;

/** Cloud supplies map-frame goals; onboard move_base plans and avoids obstacles. */
public record NavigationOrder(String action, String missionId, String mapId,
                              List<Point> points, int cycles) {
    public record Point(double x, double y, double yaw) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(yaw)
                    || Math.abs(x) > 10000 || Math.abs(y) > 10000 || Math.abs(yaw) > Math.PI)
                throw BusinessException.invalid("目标坐标必须为有限值，范围±10000m，yaw范围±π");
        }
    }
}
