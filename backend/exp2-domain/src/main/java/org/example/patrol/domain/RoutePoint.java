package org.example.patrol.domain;

public record RoutePoint(int sequence, double x, double y, Double yaw) {
    public RoutePoint {
        if (sequence < 0
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || (yaw != null && (!Double.isFinite(yaw) || Math.abs(yaw) > Math.PI)))
            throw BusinessException.invalid("点位序号须非负，坐标须为有限数，yaw须在[-π,π]内");
    }
}
