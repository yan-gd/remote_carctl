package org.example.patrol.infrastructure.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.patrol.domain.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

final class NavigationMessages {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATES = Set.of("IDLE", "PENDING", "ACTIVE", "SUCCEEDED", "ABORTED", "CANCELLED", "REJECTED", "UNAVAILABLE");

    static byte[] order(NavigationOrder order, Instant now, long seq, String session, String robotId) {
        try {
            return JSON.writeValueAsBytes(Map.of("robot_id", robotId, "seq", seq, "session", session,
                    "expires_at", now.plusSeconds(3).toEpochMilli(), "order", order));
        } catch (Exception e) { throw new IllegalArgumentException("无法编码导航请求", e); }
    }

    static NavigationReport report(byte[] bytes, String robotId) {
        if (bytes == null || bytes.length > 16384) return null;
        try {
            JsonNode n = JSON.readTree(bytes);
            if (!robotId.equals(n.path("robot_id").asText()) || !n.path("ready").isBoolean()
                    || !STATES.contains(n.path("state").asText())) return null;
            String map = text(n, "map_id"), mission = text(n, "mission_id");
            String mode = n.has("mode") ? text(n, "mode") : "map";
            if (!Set.of("map", "relative").contains(mode == null ? "" : mode)) return null;
            if ("relative".equals(mode) && !"odom".equals(text(n, "frame"))) return null;
            if ((map != null && map.length() > 128) || (mission != null && mission.length() > 64)) return null;
            JsonNode obstacle = n.path("obstacle_detected");
            if (!obstacle.isMissingNode() && !obstacle.isNull() && !obstacle.isBoolean()) return null;
            Boolean detected = obstacle.isBoolean() ? obstacle.asBoolean() : null;
            Double distance = number(n, "obstacle_distance");
            if (Boolean.TRUE.equals(detected) && (distance == null || distance <= 0 || distance > 0.8)) return null;
            if (!Boolean.TRUE.equals(detected)) distance = null;
            return new NavigationReport(robotId, n.path("ready").asBoolean(), text(n, "reason"), map,
                    text(n, "frame"), n.path("state").asText(), mission,
                    n.path("point_index").asInt(), n.path("total_points").asInt(),
                    number(n, "x"), number(n, "y"), number(n, "yaw"), mode, detected, distance);
        } catch (Exception e) { return null; }
    }

    private static String text(JsonNode n, String field) {
        return n.path(field).isTextual() ? n.path(field).asText().substring(0, Math.min(160, n.path(field).asText().length())) : null;
    }
    private static Double number(JsonNode n, String field) {
        return n.path(field).isNumber() && Double.isFinite(n.path(field).asDouble()) ? n.path(field).asDouble() : null;
    }
}
