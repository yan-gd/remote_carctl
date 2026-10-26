package org.example.patrol.domain;

/** Actual move_base result/status, not a database task lifecycle. */
public record NavigationReport(String robotId, boolean ready, String reason, String mapId,
                               String frame, String state, String missionId, int pointIndex,
                               int totalPoints, Double x, Double y, Double yaw, String mode,
                               Boolean obstacleDetected, Double obstacleDistance) {
    public NavigationReport(String robotId, boolean ready, String reason, String mapId,
                            String frame, String state, String missionId, int pointIndex,
                            int totalPoints, Double x, Double y, Double yaw, String mode) {
        this(robotId, ready, reason, mapId, frame, state, missionId, pointIndex, totalPoints,
                x, y, yaw, mode, null, null);
    }
    /** Older robot reports and callers retain saved-map semantics. */
    public NavigationReport(String robotId, boolean ready, String reason, String mapId,
                            String frame, String state, String missionId, int pointIndex,
                            int totalPoints, Double x, Double y, Double yaw) {
        this(robotId, ready, reason, mapId, frame, state, missionId, pointIndex, totalPoints, x, y, yaw, "map");
    }
}
