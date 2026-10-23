package org.example.patrol.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.example.patrol.domain.*;
import org.example.patrol.infrastructure.mqtt.RobotMessages;
import org.junit.jupiter.api.Test;

import java.time.Instant;

class RobotMessagesTest {
    final RobotMessages messages = new RobotMessages("SCOUT-001");
    final ObjectMapper json = new ObjectMapper();

    static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }

    @Test
    void controlPayloadMatchesTheProtocol() throws Exception {
        var node =
                json.readTree(
                        messages.control(
                                RobotCommand.FORWARD,
                                Instant.ofEpochMilli(1780022123000L),
                                7,
                                "session-1"));
        assertEquals("FORWARD", node.path("command").asText());
        assertEquals(1780022123000L, node.path("timestamp").asLong());
        assertEquals(7, node.path("seq").asLong());
        assertEquals("session-1", node.path("session").asText());
        assertEquals("SCOUT-001", node.path("robot_id").asText());
        assertEquals(5, node.size(), "no speed or other field is ever sent");
    }

    @Test
    void heartbeatVariants() {
        var alive = messages.heartbeat(bytes("{\"online\":true,\"chassis_connected\":true}"));
        assertTrue(alive.online());
        assertEquals(Boolean.TRUE, alive.chassisConnected());
        assertNull(alive.reason());
        assertTrue(messages.heartbeat(bytes("{}")).online(), "a heartbeat by itself means alive");
        assertNull(messages.heartbeat(bytes("{\"chassis_connected\":\"yes\"}")).chassisConnected());
        var will = messages.heartbeat(bytes("{\"online\":false,\"reason\":\"LWT\"}"));
        assertFalse(will.online());
        assertEquals("LWT", will.reason());
        assertNull(will.chassisConnected());
        assertEquals("UNKNOWN", messages.heartbeat(bytes("{\"online\":false}")).reason());
        assertEquals(
                "UNKNOWN",
                messages.heartbeat(bytes("{\"online\":false,\"reason\":\"<b>drop table</b>\"}"))
                        .reason());
    }

    @Test
    void malformedOrForeignPayloadsAreDropped() {
        assertNull(messages.heartbeat(bytes("{\"online\":\"yes\"}")));
        assertNull(messages.heartbeat(bytes("{\"online\":true,\"robot_id\":\"OTHER\"}")));
        assertNull(messages.heartbeat(bytes("{\"online\":true,\"robot_id\":7}")));
        assertNotNull(messages.heartbeat(bytes("{\"online\":true,\"robot_id\":\"SCOUT-001\"}")));
        assertNull(messages.heartbeat(bytes("not json")));
        assertNull(messages.heartbeat(bytes("[1,2]")));
        assertNull(messages.heartbeat(new byte[0]));
        assertNull(messages.heartbeat(null));
        byte[] huge = new byte[8 * 1024 + 1];
        java.util.Arrays.fill(huge, (byte) ' ');
        huge[0] = '{';
        huge[huge.length - 1] = '}';
        assertNull(messages.heartbeat(huge));
        assertNull(messages.status(bytes("\"text\"")));
    }

    @Test
    void statusKeepsOnlyValidValues() {
        var t =
                messages.status(
                        bytes(
                                "{\"robot_id\":\"SCOUT-001\",\"motion_state\":\"FORWARD\",\"battery_pct\":null,"
                                        + "\"battery_voltage\":25.4,\"linear_velocity\":0.3,\"angular_velocity\":\"fast\","
                                        + "\"position\":{\"x\":1.5,\"y\":-2,\"yaw\":0.25},\"timestamp\":1}"));
        assertEquals(MotionState.FORWARD, t.motionState());
        assertNull(t.batteryPct());
        assertEquals(25.4, t.batteryVoltage());
        assertEquals(0.3, t.linearVelocity());
        assertNull(t.angularVelocity());
        assertEquals(1.5, t.x());
        assertEquals(-2.0, t.y());
        assertEquals(0.25, t.yaw());
        var empty = messages.status(bytes("{\"motion_state\":\"FLYING\",\"position\":5}"));
        assertEquals(MotionState.UNKNOWN, empty.motionState());
        assertNull(empty.x());
        assertNull(empty.batteryVoltage());
    }

    @Test
    void receiptsFromOtherSessionsOrMalformedOnesAreDropped() {
        var ack =
                messages.ack(
                        bytes(
                                "{\"seq\":3,\"session\":\"s-1\",\"command\":\"STOP\",\"result\":\"APPLIED\"}"),
                        "s-1");
        assertEquals(3, ack.seq());
        assertEquals("STOP", ack.command());
        assertEquals("APPLIED", ack.result());
        assertNull(ack.reason());
        var refused =
                messages.ack(
                        bytes(
                                "{\"seq\":4,\"command\":\"JUMP\",\"result\":\"REJECTED\",\"reason\":\"INVALID_COMMAND\"}"),
                        "s-1");
        assertEquals("UNKNOWN", refused.command());
        assertEquals("INVALID_COMMAND", refused.reason());
        assertNull(messages.ack(bytes("{\"seq\":3,\"session\":\"old\",\"result\":\"APPLIED\"}"), "s-1"));
        assertNull(messages.ack(bytes("{\"seq\":-1,\"result\":\"APPLIED\"}"), "s-1"));
        assertNull(messages.ack(bytes("{\"seq\":1.5,\"result\":\"APPLIED\"}"), "s-1"));
        assertNull(messages.ack(bytes("{\"seq\":\"1\",\"result\":\"APPLIED\"}"), "s-1"));
        assertNull(messages.ack(bytes("{\"seq\":1,\"result\":\"DONE\"}"), "s-1"));
        assertNull(messages.ack(bytes("{\"result\":\"APPLIED\"}"), "s-1"));
    }
}
