package org.example.patrol.domain;

/** Actual move_base result/status, not a database task lifecycle. */
public record NavigationReport(String robotId, boolean ready, String reason, String mapId,
                               String frame, String state, String missionId, int pointIndex,
                               int totalPoints, Double x, Double y, Double yaw) {}
