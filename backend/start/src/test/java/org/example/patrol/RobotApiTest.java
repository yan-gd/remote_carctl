package org.example.patrol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;

import org.example.Application;
import org.example.patrol.app.RobotMonitor;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

/** Robot endpoints through the real security chain, SQLite audit/alarm tables and RobotMonitor. */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(RobotApiTest.FakeLink.class)
class RobotApiTest {
    static final Path DB = PersistenceTest.tempDatabase();
    static final String PASSWORD = "TestPassword123!";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("patrol.database.path", () -> DB.toString());
        r.add("patrol.robot.heartbeat-timeout-ms", () -> "1000");
        r.add("patrol.robot.check-interval-ms", () -> "100");
    }

    @TestConfiguration
    static class FakeLink {
        @Bean
        @Primary
        FakeRobotGateway fakeRobotGateway() {
            return new FakeRobotGateway();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserGateway users;
    @Autowired PasswordGateway passwords;
    @Autowired DataSource ds;
    @Autowired RobotMonitor monitor;
    @Autowired FakeRobotGateway link;
    final Map<String, String> tokens = new HashMap<>();
    JdbcTemplate jdbc;
    String hash;

    @BeforeAll
    void fixtures() {
        jdbc = new JdbcTemplate(ds);
        hash = passwords.hash(PASSWORD);
    }

    @BeforeEach
    void healthyLink() {
        link.connected = true;
        link.failure = null;
        monitor.onLink(true);
    }

    String token(String name, Role role) throws Exception {
        if (!tokens.containsKey(name)) {
            if (users.userByName(name) == null)
                users.insertUser(new User(null, name, hash, role, Instant.now()));
            var login =
                    call(
                            post("/api/v1/auth/login"),
                            Map.of("username", name, "password", PASSWORD),
                            null,
                            200);
            tokens.put(name, login.path("data").path("token").asText());
        }
        return tokens.get(name);
    }

    String token(Role role) throws Exception {
        return token("robot_" + role.name().toLowerCase(Locale.ROOT), role);
    }

    JsonNode call(MockHttpServletRequestBuilder req, Object body, String token, int status)
            throws Exception {
        if (body != null)
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        String content =
                mvc.perform(req)
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(UTF_8);
        var node = json.readTree(content);
        assertTrue(node.has("code"));
        assertTrue(node.has("data"));
        return node;
    }

    JsonNode control(String command, String token, int status) throws Exception {
        return call(post("/api/v1/robot/control"), Map.of("command", command), token, status);
    }

    String code(JsonNode response) {
        return response.path("code").asText();
    }

    void online() {
        monitor.onHeartbeat(new RobotHeartbeat(true, true, null));
    }

    void offline() {
        monitor.onHeartbeat(new RobotHeartbeat(false, null, "LWT"));
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void everyRoleCanReadRobotInfoAndStatusButNobodyAnonymously() throws Exception {
        for (Role role : Role.values()) {
            var info = call(get("/api/v1/robot"), null, token(role), 200).path("data");
            assertEquals("SCOUT-001", info.path("robotId").asText());
            assertEquals("SCOUT MINI", info.path("model").asText());
            assertEquals(
                    List.of("FORWARD", "BACKWARD", "LEFT", "RIGHT", "STOP"),
                    json.convertValue(info.path("commands"), List.class));
            assertTrue(info.path("mqttConnected").asBoolean());
            call(get("/api/v1/robot/status"), null, token(role), 200);
        }
        int sent = link.published.size();
        call(get("/api/v1/robot"), null, null, 401);
        call(get("/api/v1/robot/status"), null, null, 401);
        assertEquals("UNAUTHORIZED", code(control("STOP", null, 401)));
        assertEquals(sent, link.published.size(), "anonymous requests never reach MQTT");
    }

    @Test
    void statusShowsTelemetryOnlyWhileOnline() throws Exception {
        offline();
        var s = call(get("/api/v1/robot/status"), null, token(Role.VIEWER), 200).path("data");
        assertFalse(s.path("online").asBoolean());
        assertEquals("UNKNOWN", s.path("motionState").asText());
        assertTrue(s.path("batteryPct").isNull());
        assertTrue(s.path("linearVelocity").isNull());
        assertTrue(s.path("position").path("x").isNull());
        assertTrue(s.path("chassisConnected").isNull());
        assertTrue(s.path("mqttConnected").asBoolean());

        online();
        monitor.onStatus(
                new RobotTelemetry(MotionState.FORWARD, null, 25.2, 0.3, 0.0, 1.5, 2.5, 0.1));
        monitor.onAck(new RobotAck(9, "FORWARD", "APPLIED", null));
        s = call(get("/api/v1/robot/status"), null, token(Role.VIEWER), 200).path("data");
        assertTrue(s.path("online").asBoolean());
        assertEquals("FORWARD", s.path("motionState").asText());
        assertTrue(s.path("batteryPct").isNull(), "no percentage is invented from the voltage");
        assertEquals(25.2, s.path("batteryVoltage").asDouble());
        assertEquals(0.3, s.path("linearVelocity").asDouble());
        assertEquals(1.5, s.path("position").path("x").asDouble());
        assertEquals(0.1, s.path("position").path("yaw").asDouble());
        assertTrue(s.path("chassisConnected").asBoolean());
        assertFalse(s.path("lastSeenAt").isNull());
        assertFalse(s.path("telemetryAt").isNull());
        assertEquals(9, s.path("lastAck").path("seq").asLong());
        assertEquals("APPLIED", s.path("lastAck").path("result").asText());
        Instant.parse(s.path("lastSeenAt").asText()); // ISO-8601 UTC like the rest of the API
    }

    @Test
    void controlRoleMatrix() throws Exception {
        String operator = token(Role.OPERATOR), monitorToken = token(Role.MONITOR);
        online();
        var accepted = control("FORWARD", operator, 200).path("data");
        assertTrue(accepted.path("accepted").asBoolean());
        assertEquals("FORWARD", accepted.path("command").asText());
        assertTrue(accepted.path("seq").asLong() > 0);
        Instant.parse(accepted.path("timestamp").asText());
        for (String command : List.of("BACKWARD", "LEFT", "RIGHT", "STOP"))
            control(command, operator, 200);
        control("STOP", monitorToken, 200);
        assertEquals("FORBIDDEN", code(control("FORWARD", monitorToken, 403)));
        assertEquals("FORBIDDEN", code(control("STOP", token(Role.MAINTAINER), 403)));
        assertEquals("FORBIDDEN", code(control("STOP", token(Role.VIEWER), 403)));
    }

    @Test
    void controlErrorsUseTheRequirementCodes() throws Exception {
        String operator = token(Role.OPERATOR);
        online();
        assertEquals("INVALID_COMMAND", code(control("JUMP", operator, 400)));
        assertEquals("INVALID_COMMAND", code(control("forward", operator, 400)));
        assertEquals(
                "INVALID_COMMAND",
                code(call(post("/api/v1/robot/control"), Map.of(), operator, 400)));
        assertEquals(
                "INVALID_ARGUMENT",
                code(
                        call(
                                post("/api/v1/robot/control"),
                                Map.of("command", "STOP", "speed", 2),
                                operator,
                                400)));

        offline();
        int sent = link.published.size();
        assertEquals("ROBOT_OFFLINE", code(control("FORWARD", operator, 409)));
        assertEquals(sent, link.published.size(), "motion is never published while offline");
        control("STOP", operator, 200);
        assertEquals(RobotCommand.STOP, link.published.get(link.published.size() - 1));

        link.connected = false;
        assertEquals("MQTT_UNAVAILABLE", code(control("STOP", operator, 503)));
        assertEquals("MQTT_UNAVAILABLE", code(control("FORWARD", operator, 503)));

        link.connected = true;
        online();
        link.failure = BusinessException.unavailable("MQTT_UNAVAILABLE", "broker did not confirm");
        assertEquals("MQTT_UNAVAILABLE", code(control("LEFT", operator, 503)));
    }

    @Test
    void controlLogKeepsCommandsButNotKeepalives() throws Exception {
        String operator = token("log_operator", Role.OPERATOR);
        String watcher = token("log_monitor", Role.MONITOR);
        long operatorId = users.userByName("log_operator").id();
        long watcherId = users.userByName("log_monitor").id();
        String successes =
                "SELECT count(*) FROM control_log WHERE operation='ROBOT_CONTROL' AND actor_id=?"
                        + " AND target=? AND result='SUCCESS' AND error IS NULL";
        String failures =
                "SELECT count(*) FROM control_log WHERE operation='ROBOT_CONTROL' AND actor_id=?"
                        + " AND target=? AND result='FAILURE' AND error=?";
        String generic =
                "SELECT count(*) FROM control_log WHERE operation='BUSINESS_FAILURE'"
                        + " AND target='POST /api/v1/robot/control'";
        int sent = link.published.size();
        online();
        for (int i = 0; i < 5; i++) control("BACKWARD", operator, 200);
        control("STOP", operator, 200);
        assertEquals(6, link.published.size() - sent, "every keepalive is still published");
        assertEquals(1, count(successes, operatorId, "robot:SCOUT-001:BACKWARD"));
        assertEquals(1, count(successes, operatorId, "robot:SCOUT-001:STOP"));

        long genericBefore = count(generic);
        control("FORWARD", watcher, 403);
        control("FORWARD", watcher, 403);
        assertEquals(1, count(failures, watcherId, "robot:SCOUT-001:FORWARD", "FORBIDDEN"));
        assertEquals(genericBefore, count(generic), "control failures are not logged twice");

        var page =
                call(
                                get("/api/v1/control-logs?operation=ROBOT_CONTROL&size=100"),
                                null,
                                token(Role.MAINTAINER),
                                200)
                        .path("data");
        assertTrue(page.path("total").asInt() >= 3);
        String dump = json.writeValueAsString(jdbc.queryForList("SELECT * FROM control_log"));
        assertFalse(dump.contains(operator), "tokens never reach the log table");
    }

    @Test
    void heartbeatLossRaisesOneAlarmAndBlocksMotion() throws Exception {
        String sql = "SELECT count(*) FROM alarm_record WHERE source='SCOUT-001' AND title='小车离线'";
        offline();
        Thread.sleep(300); // an alarm the scheduler raised for an earlier test is stored by now
        long before = count(sql);
        online();
        assertTrue(monitor.online());
        long deadline = System.currentTimeMillis() + 5000;
        while (count(sql) == before && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(400); // later scheduler runs must not repeat the alarm
        assertEquals(before + 1, count(sql));
        assertFalse(monitor.online());
        assertEquals("ROBOT_OFFLINE", code(control("FORWARD", token(Role.OPERATOR), 409)));

        var items =
                call(
                                get("/api/v1/alarms?level=CRITICAL&status=UNACKNOWLEDGED&size=100"),
                                null,
                                token(Role.VIEWER),
                                200)
                        .path("data")
                        .path("items");
        long id = -1;
        for (var alarm : items)
            if ("SCOUT-001".equals(alarm.path("source").asText())
                    && alarm.path("description").asText().contains("未收到心跳"))
                id = alarm.path("id").asLong();
        assertTrue(id > 0, "the alarm is visible through the alarm API");
        var ack = call(patch("/api/v1/alarms/" + id + "/ack"), null, token(Role.MONITOR), 200);
        assertEquals("ACKNOWLEDGED", ack.path("data").path("status").asText());
    }

    @Test
    void brokerOutageRaisesOneLinkAlarm() throws Exception {
        String sql = "SELECT count(*) FROM alarm_record WHERE source='MQTT'";
        long before = count(sql);
        link.connected = false;
        monitor.onLink(false);
        monitor.onLink(false);
        assertEquals(before + 1, count(sql));
        var s = call(get("/api/v1/robot/status"), null, token(Role.VIEWER), 200).path("data");
        assertFalse(s.path("mqttConnected").asBoolean());
        assertFalse(s.path("online").asBoolean());
        assertEquals("MQTT_UNAVAILABLE", code(control("STOP", token(Role.MONITOR), 503)));
        link.connected = true;
        monitor.onLink(true);
        assertEquals(before + 1, count(sql), "reconnecting is not an alarm");
    }
}
