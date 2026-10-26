package org.example.patrol;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;

import org.example.Application;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.example.patrol.infrastructure.security.JwtTokenGateway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import javax.sql.DataSource;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIntegrationTest {
    static final Path DB = PersistenceTest.tempDatabase();
    static final String SECRET =
            Base64.getEncoder()
                    .encodeToString(
                            "test-only-signing-key-32-bytes-long"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    static final String PASSWORD = "TestPassword123!";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("patrol.database.path", () -> DB.toString());
        r.add("patrol.jwt.secret", () -> SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserGateway users;
    @Autowired PasswordGateway passwords;
    @Autowired DataSource ds;
    final Map<Role, String> tokens = new EnumMap<>(Role.class);
    JdbcTemplate jdbc;

    @BeforeAll
    void fixtures() throws Exception {
        jdbc = new JdbcTemplate(ds);
        String hash = passwords.hash(PASSWORD);
        for (Role role : Role.values()) {
            users.insertUser(new User(null, "test_" + role.name(), hash, role, Instant.now()));
            var result =
                    call(
                            post("/api/v1/auth/login"),
                            Map.of("username", "test_" + role.name(), "password", PASSWORD),
                            null,
                            200);
            tokens.put(role, result.path("data").path("token").asText());
        }
    }

    JsonNode call(MockHttpServletRequestBuilder req, Object body, Role role, int status)
            throws Exception {
        if (body != null)
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (role != null) req.header("Authorization", "Bearer " + tokens.get(role));
        String content =
                mvc.perform(req)
                        .andExpect(status().is(status))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var node = json.readTree(content);
        assertTrue(node.has("code"));
        assertTrue(node.has("data"));
        return node;
    }

    Map<String, Object> routeBody(String name) {
        return Map.of(
                "name",
                name,
                "description",
                "API测试",
                "points",
                List.of(
                        Map.of("sequence", 2, "x", 1.25, "y", 2.5),
                        Map.of("sequence", 0, "x", 0, "y", 0, "yaw", 0)));
    }

    long route() throws Exception {
        return call(post("/api/v1/routes"), routeBody("测试路线"), Role.OPERATOR, 201)
                .path("data")
                .path("id")
                .asLong();
    }

    Map<String, Object> taskBody(long route, String status) {
        return Map.of(
                "name",
                "测试任务",
                "routeId",
                route,
                "status",
                status,
                "plannedAt",
                "2026-11-01T08:00:00+08:00");
    }

    long alarmFixture() {
        jdbc.update(
                "INSERT INTO alarm_record(title,description,level,status,source,occurred_at)"
                        + " VALUES('测试','夹具','CRITICAL','UNACKNOWLEDGED','TEST_FIXTURE',?)",
                Instant.parse("2026-10-19T00:00:00Z").toEpochMilli());
        return jdbc.queryForObject("SELECT max(id) FROM alarm_record", Long.class);
    }

    @Test
    void registrationLoginCurrentUserAndDuplicate() throws Exception {
        var credentials = Map.of("username", "new_viewer", "password", PASSWORD);
        var user = call(post("/api/v1/auth/register"), credentials, null, 201).path("data");
        assertEquals("VIEWER", user.path("role").asText());
        assertFalse(user.has("passwordHash"));
        String hash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM sys_user WHERE username='new_viewer'",
                        String.class);
        assertNotEquals(PASSWORD, hash);
        assertTrue(passwords.matches(PASSWORD, hash));
        assertEquals(
                "BUSINESS_CONFLICT",
                call(
                                post("/api/v1/auth/register"),
                                Map.of("username", "NEW_VIEWER", "password", PASSWORD),
                                null,
                                409)
                        .path("code")
                        .asText());
        var login = call(post("/api/v1/auth/login"), credentials, null, 200).path("data");
        assertEquals("Bearer", login.path("tokenType").asText());
        assertEquals(3600, login.path("expiresIn").asInt());
        var me =
                call(
                        get("/api/v1/auth/me")
                                .header("Authorization", "Bearer " + login.path("token").asText()),
                        null,
                        null,
                        200);
        assertEquals("new_viewer", me.path("data").path("username").asText());
        assertEquals(
                "LOGIN_FAILED",
                call(
                                post("/api/v1/auth/login"),
                                Map.of("username", "new_viewer", "password", "WrongPassword!"),
                                null,
                                401)
                        .path("code")
                        .asText());
        call(
                post("/api/v1/auth/login"),
                Map.of("username", "no_such_user", "password", PASSWORD),
                null,
                401);
        call(
                post("/api/v1/auth/register"),
                Map.of("username", "privileged", "password", PASSWORD, "role", "OPERATOR"),
                null,
                400);
    }

    @Test
    void invalidExpiredAndMissingTokens() throws Exception {
        call(get("/api/v1/routes"), null, null, 401);
        call(get("/api/v1/routes").header("Authorization", "Bearer garbage"), null, null, 401);
        call(get("/api/v1/routes").header("Authorization", "Basic xxx"), null, null, 401);
        var old =
                new JwtTokenGateway(
                        SECRET, 1, Clock.fixed(Instant.now().minusSeconds(100), ZoneOffset.UTC));
        String expired = old.issue(users.userByName("test_VIEWER"));
        assertEquals(
                "TOKEN_EXPIRED",
                call(
                                get("/api/v1/routes").header("Authorization", "Bearer " + expired),
                                null,
                                null,
                                401)
                        .path("code")
                        .asText());
        var missingUser = new User(999999999L, "deleted", null, Role.VIEWER, Instant.now());
        String token = new JwtTokenGateway(SECRET, 3600, Clock.systemUTC()).issue(missingUser);
        call(get("/api/v1/routes").header("Authorization", "Bearer " + token), null, null, 401);
    }

    @Test
    void enforcesEntireRoleMatrix() throws Exception {
        long id = route(), alarm = alarmFixture();
        for (Role role : Role.values()) {
            call(get("/api/v1/routes"), null, role, 200);
            call(get("/api/v1/tasks"), null, role, 200);
            call(get("/api/v1/alarms"), null, role, 200);
            call(get("/api/v1/control-logs"), null, role, 200);
            if (role != Role.OPERATOR) {
                call(post("/api/v1/routes"), routeBody("越权"), role, 403);
                call(put("/api/v1/routes/" + id), routeBody("越权"), role, 403);
                call(delete("/api/v1/routes/" + id), null, role, 403);
                call(post("/api/v1/tasks"), taskBody(id, "DRAFT"), role, 403);
                call(put("/api/v1/tasks/1"), taskBody(id, "DRAFT"), role, 403);
                call(delete("/api/v1/tasks/1"), null, role, 403);
            }
            if (role != Role.MONITOR)
                call(patch("/api/v1/alarms/" + alarm + "/ack"), null, role, 403);
        }
        call(patch("/api/v1/alarms/" + alarm + "/ack"), null, Role.MONITOR, 200);
    }

    @Test
    void routeLifecycleAndPagination() throws Exception {
        long id = route();
        var detail = call(get("/api/v1/routes/" + id), null, Role.VIEWER, 200).path("data");
        assertEquals(0, detail.path("points").get(0).path("sequence").asInt());
        var updated =
                call(put("/api/v1/routes/" + id), routeBody("更新路线"), Role.OPERATOR, 200)
                        .path("data");
        assertEquals("更新路线", updated.path("name").asText());
        var page = call(get("/api/v1/routes?page=1&size=1"), null, Role.VIEWER, 200).path("data");
        assertEquals(1, page.path("items").size());
        assertEquals(1, page.path("page").asInt());
        assertTrue(page.path("total").asLong() >= 1);
        call(delete("/api/v1/routes/" + id), null, Role.OPERATOR, 200);
        call(get("/api/v1/routes/" + id), null, Role.VIEWER, 404);
        call(put("/api/v1/routes/" + id), routeBody("不存在"), Role.OPERATOR, 404);
        call(delete("/api/v1/routes/" + id), null, Role.OPERATOR, 404);
    }

    @Test
    void routeRejectsBadPointsAndParameters() throws Exception {
        call(post("/api/v1/routes"), routeBody("   "), Role.OPERATOR, 400);
        call(
                post("/api/v1/routes"),
                Map.of("name", "空点位", "points", List.of()),
                Role.OPERATOR,
                400);
        call(
                post("/api/v1/routes"),
                Map.of(
                        "name",
                        "重复",
                        "points",
                        List.of(
                                Map.of("sequence", 0, "x", 0, "y", 0),
                                Map.of("sequence", 0, "x", 1, "y", 1))),
                Role.OPERATOR,
                400);
        call(
                post("/api/v1/routes"),
                Map.of(
                        "name",
                        "非法yaw",
                        "points",
                        List.of(Map.of("sequence", 0, "x", 0, "y", 0, "yaw", 4))),
                Role.OPERATOR,
                400);
        mvc.perform(
                        post("/api/v1/routes")
                                .header("Authorization", "Bearer " + tokens.get(Role.OPERATOR))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{bad-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        call(get("/api/v1/routes/-1"), null, Role.VIEWER, 400);
    }

    @Test
    void taskLifecycleReferencesAndStates() throws Exception {
        long route = route();
        var created =
                call(post("/api/v1/tasks"), taskBody(route, "DRAFT"), Role.OPERATOR, 201)
                        .path("data");
        long id = created.path("id").asLong();
        long creator = users.userByName("test_OPERATOR").id();
        assertEquals(creator, created.path("creatorId").asLong());
        call(delete("/api/v1/routes/" + route), null, Role.OPERATOR, 409);
        call(put("/api/v1/tasks/" + id), taskBody(route, "PLANNED"), Role.OPERATOR, 200);
        assertEquals(
                "PLANNED",
                call(get("/api/v1/tasks/" + id), null, Role.VIEWER, 200)
                        .path("data")
                        .path("status")
                        .asText());
        var filtered =
                call(get("/api/v1/tasks?status=PLANNED&size=1"), null, Role.VIEWER, 200)
                        .path("data");
        assertTrue(filtered.path("total").asInt() > 0);
        assertEquals("PLANNED", filtered.path("items").get(0).path("status").asText());
        call(put("/api/v1/tasks/" + id), taskBody(route, "DRAFT"), Role.OPERATOR, 200);
        call(put("/api/v1/tasks/" + id), taskBody(route, "CANCELLED"), Role.OPERATOR, 200);
        call(put("/api/v1/tasks/" + id), taskBody(route, "DRAFT"), Role.OPERATOR, 409);
        call(delete("/api/v1/tasks/" + id), null, Role.OPERATOR, 200);
        call(get("/api/v1/tasks/" + id), null, Role.VIEWER, 404);
        call(put("/api/v1/tasks/" + id), taskBody(route, "DRAFT"), Role.OPERATOR, 404);
        call(delete("/api/v1/tasks/" + id), null, Role.OPERATOR, 404);
        call(delete("/api/v1/routes/" + route), null, Role.OPERATOR, 200);
    }

    @Test
    void invalidTaskInputs() throws Exception {
        call(post("/api/v1/tasks"), taskBody(9999999, "DRAFT"), Role.OPERATOR, 404);
        long route = route();
        call(post("/api/v1/tasks"), taskBody(route, "RUNNING"), Role.OPERATOR, 400);
        call(post("/api/v1/tasks"), taskBody(route, "CANCELLED"), Role.OPERATOR, 400);
        call(
                post("/api/v1/tasks"),
                Map.of("name", "未提供计划时间", "routeId", route, "status", "PLANNED"),
                Role.OPERATOR,
                400);
        call(
                post("/api/v1/tasks"),
                Map.of("name", "越权创建人", "routeId", route, "status", "DRAFT", "creatorId", 1),
                Role.OPERATOR,
                400);
    }

    @Test
    void alarmsAcknowledgeFilterAndRepeatedConflict() throws Exception {
        long id = alarmFixture();
        assertEquals(
                "TEST_FIXTURE",
                call(get("/api/v1/alarms/" + id), null, Role.VIEWER, 200)
                        .path("data")
                        .path("source")
                        .asText());
        var page =
                call(
                                get("/api/v1/alarms")
                                        .param("level", "CRITICAL")
                                        .param("status", "UNACKNOWLEDGED")
                                        .param("from", "2026-10-19T00:00:00Z")
                                        .param("to", "2026-10-19T00:00:00Z"),
                                null,
                                Role.VIEWER,
                                200)
                        .path("data");
        assertTrue(page.path("total").asInt() > 0);
        var ack =
                call(patch("/api/v1/alarms/" + id + "/ack"), null, Role.MONITOR, 200).path("data");
        assertEquals("ACKNOWLEDGED", ack.path("status").asText());
        assertEquals(
                users.userByName("test_MONITOR").id().longValue(),
                ack.path("acknowledgedBy").asLong());
        assertFalse(ack.path("acknowledgedAt").isNull());
        call(patch("/api/v1/alarms/" + id + "/ack"), null, Role.MONITOR, 409);
        call(get("/api/v1/alarms/999999"), null, Role.VIEWER, 404);
        call(patch("/api/v1/alarms/999999/ack"), null, Role.MONITOR, 404);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM control_log WHERE operation='ALARM_ACK' AND target=?",
                        Integer.class,
                        "alarm:" + id));
    }

    @Test
    void logsAreAutomaticFilterableAndCredentialFree() throws Exception {
        long id = route();
        call(get("/api/v1/routes"), null, null, 401);
        var page =
                call(
                                get("/api/v1/control-logs")
                                        .param("operation", "ROUTE_CREATE")
                                        .param("size", "100")
                                        .param("from", "2020-01-01T00:00:00Z")
                                        .param("to", "2099-01-01T00:00:00Z"),
                                null,
                                Role.VIEWER,
                                200)
                        .path("data");
        assertTrue(page.path("total").asInt() > 0);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM control_log WHERE operation='ROUTE_CREATE' AND"
                                + " target=? AND result='SUCCESS'",
                        Integer.class,
                        "route:" + id));
        assertTrue(
                jdbc.queryForObject(
                                "SELECT count(*) FROM control_log WHERE operation='AUTH_FAILURE'"
                                        + " AND result='FAILURE'",
                                Integer.class)
                        > 0);
        String dump = json.writeValueAsString(jdbc.queryForList("SELECT * FROM control_log"));
        assertFalse(dump.contains(PASSWORD));
        assertFalse(dump.contains(tokens.get(Role.OPERATOR)));
        assertFalse(dump.contains("$2a$"));
        assertEquals(
                0,
                call(get("/api/v1/control-logs?operation=UNKNOWN"), null, Role.VIEWER, 200)
                        .path("data")
                        .path("total")
                        .asInt());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/routes?page=0",
                "/routes?size=101",
                "/routes?page=x",
                "/tasks?status=RUNNING",
                "/alarms?level=WRONG",
                "/alarms?status=WRONG",
                "/alarms?from=invalid",
                "/alarms?from=2026-10-20T00:00:00Z&to=2026-10-19T00:00:00Z",
                "/control-logs?operation=bad-value",
                "/control-logs?size=0"
            })
    void queryValidation(String path) throws Exception {
        assertEquals(
                "INVALID_ARGUMENT",
                call(get("/api/v1" + path), null, Role.VIEWER, 400).path("code").asText());
    }

    @Test
    void swaggerActuallyDescribesAllOperations() throws Exception {
        var doc =
                json.readTree(
                        mvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        int operations = 0;
        for (var p : doc.path("paths")) {
            for (var op : p) {
                assertTrue(op.hasNonNull("summary"));
                assertTrue(op.path("responses").has("401"));
                operations++;
            }
        }
        // 20 existing + 3 navigation + 4 camera + 5 Web mapping operations.
        assertEquals(32, operations);
        assertTrue(
                doc.path("paths")
                        .path("/api/v1/robot/control")
                        .path("post")
                        .path("responses")
                        .has("503"));
        assertFalse(doc.path("components").path("schemas").has("Actor"));
        assertEquals(
                "bearer",
                doc.path("components")
                        .path("securitySchemes")
                        .path("bearerAuth")
                        .path("scheme")
                        .asText());
        assertTrue(doc.path("components").path("schemas").has("RouteCommand"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void actualSqliteFailureBecomesSafe500() throws Exception {
        jdbc.execute("ALTER TABLE patrol_route RENAME TO unavailable_route_fixture");
        try {
            var response = call(get("/api/v1/routes"), null, Role.VIEWER, 500);
            assertEquals("DATABASE_ERROR", response.path("code").asText());
            assertFalse(response.toString().contains("SELECT"));
            assertFalse(response.toString().contains("patrol_route"));
        } finally {
            jdbc.execute("ALTER TABLE unavailable_route_fixture RENAME TO patrol_route");
        }
    }

    @Test
    void auditInsertFailureRollsBackBusinessWrite() throws Exception {
        long count = jdbc.queryForObject("SELECT count(*) FROM patrol_route", Long.class);
        jdbc.execute(
                "CREATE TRIGGER fail_audit_fixture BEFORE INSERT ON control_log BEGIN SELECT"
                        + " RAISE(ABORT,'audit failure fixture'); END");
        try {
            var request =
                    post("/api/v1/routes")
                            .header("Authorization", "Bearer " + tokens.get(Role.OPERATOR))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(routeBody("must rollback")));
            int code = mvc.perform(request).andReturn().getResponse().getStatus();
            assertTrue(code == 409 || code == 500);
            assertEquals(
                    count, jdbc.queryForObject("SELECT count(*) FROM patrol_route", Long.class));
        } finally {
            jdbc.execute("DROP TRIGGER fail_audit_fixture");
        }
    }

    @Test
    void concurrentAcknowledgementHasExactlyOneWinner() throws Exception {
        long id = alarmFixture();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> confirm =
                    () ->
                            mvc.perform(
                                            patch("/api/v1/alarms/" + id + "/ack")
                                                    .header(
                                                            "Authorization",
                                                            "Bearer " + tokens.get(Role.MONITOR)))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus();
            var results = pool.invokeAll(List.of(confirm, confirm));
            var statuses = new java.util.ArrayList<Integer>();
            for (var r : results) statuses.add(r.get());
            java.util.Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM control_log WHERE operation='ALARM_ACK' AND"
                                    + " target=?",
                            Integer.class,
                            "alarm:" + id));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logVisibilityCannotBeExpandedByRequestParameters() throws Exception {
        long route = route(), alarm = alarmFixture();
        call(patch("/api/v1/alarms/" + alarm + "/ack"), null, Role.MONITOR, 200);
        var page =
                call(get("/api/v1/control-logs?size=100&actorId=999"), null, Role.OPERATOR, 200)
                        .path("data");
        assertTrue(page.path("total").asInt() > 0);
        for (var row : page.path("items"))
            assertEquals(
                    users.userByName("test_OPERATOR").id().longValue(),
                    row.path("actorId").asLong());
        assertEquals(
                0,
                call(get("/api/v1/control-logs?operation=ALARM_ACK"), null, Role.OPERATOR, 200)
                        .path("data")
                        .path("total")
                        .asInt());
        assertTrue(
                call(get("/api/v1/control-logs?operation=ALARM_ACK"), null, Role.MAINTAINER, 200)
                                .path("data")
                                .path("total")
                                .asInt()
                        > 0);
    }

    @Test
    void unknownEndpointHasUniform404() throws Exception {
        assertEquals(
                "NOT_FOUND",
                call(get("/api/v1/nonexistent"), null, Role.VIEWER, 404).path("code").asText());
    }
}
