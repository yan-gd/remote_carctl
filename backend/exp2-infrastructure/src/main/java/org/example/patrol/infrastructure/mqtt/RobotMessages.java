package org.example.patrol.infrastructure.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;

import org.example.patrol.domain.*;

import java.io.IOException;
import java.time.Instant;

/**
 * JSON payloads exchanged with the robot bridge (see exp3/docs/MQTT-PROTOCOL.md). Decoders return
 * null for malformed input, payloads over 8 KB, or messages addressed to another robot id.
 */
public final class RobotMessages {
    static final int MAX_PAYLOAD_BYTES = 8 * 1024;
    private static final String SAFE_TEXT = "[A-Za-z0-9_.:-]{1,64}";

    private final ObjectMapper json = new ObjectMapper();
    private final String robotId;

    public RobotMessages(String robotId) {
        this.robotId = robotId;
    }

    public byte[] control(RobotCommand command, Instant issuedAt, long seq, String session) {
        var node =
                json.createObjectNode()
                        .put("robot_id", robotId)
                        .put("command", command.name())
                        .put("seq", seq)
                        .put("session", session)
                        .put("timestamp", issuedAt.toEpochMilli());
        try {
            return json.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("control payload", e);
        }
    }

    /** A heartbeat without "online" means alive; an offline one always carries a reason. */
    public RobotHeartbeat heartbeat(byte[] payload) {
        JsonNode node = read(payload);
        if (node == null) return null;
        JsonNode online = node.get("online");
        if (online != null && !online.isBoolean()) return null;
        boolean up = online == null || online.booleanValue();
        JsonNode chassis = node.get("chassis_connected");
        String reason = text(node, "reason");
        return new RobotHeartbeat(
                up,
                chassis != null && chassis.isBoolean() ? chassis.booleanValue() : null,
                up ? null : reason == null ? "UNKNOWN" : reason);
    }

    public RobotTelemetry status(byte[] payload) {
        JsonNode node = read(payload);
        if (node == null) return null;
        JsonNode position = node.path("position");
        return new RobotTelemetry(
                MotionState.of(text(node, "motion_state")),
                number(node, "battery_pct"),
                number(node, "battery_voltage"),
                number(node, "linear_velocity"),
                number(node, "angular_velocity"),
                number(position, "x"),
                number(position, "y"),
                number(position, "yaw"));
    }

    /** Receipts from an earlier backend session are dropped; their seq numbers mean nothing now. */
    public RobotAck ack(byte[] payload, String session) {
        JsonNode node = read(payload);
        if (node == null) return null;
        JsonNode seq = node.get("seq");
        String result = text(node, "result");
        String from = text(node, "session");
        if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 0)
            return null;
        if (!"APPLIED".equals(result) && !"REJECTED".equals(result)) return null;
        if (from != null && !from.equals(session)) return null;
        String command = text(node, "command");
        boolean known = false;
        for (RobotCommand c : RobotCommand.values()) known |= c.name().equals(command);
        return new RobotAck(
                seq.longValue(), known ? command : "UNKNOWN", result, text(node, "reason"));
    }

    private JsonNode read(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES)
            return null;
        try {
            JsonNode node = json.readTree(payload);
            if (node == null || !node.isObject()) return null;
            JsonNode id = node.get("robot_id");
            if (id != null && !(id.isTextual() && robotId.equals(id.textValue()))) return null;
            return node;
        } catch (IOException e) {
            return null;
        }
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.doubleValue() : null;
    }

    /** Only short identifier-like text from the robot is kept; anything else becomes null. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && value.textValue().matches(SAFE_TEXT)
                ? value.textValue()
                : null;
    }
}
