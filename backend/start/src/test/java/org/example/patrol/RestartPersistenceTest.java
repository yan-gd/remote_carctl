package org.example.patrol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.example.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import javax.sql.DataSource;

/** Starts and shuts down real embedded HTTP servers twice against one temporary SQLite file. */
class RestartPersistenceTest {
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    ConfigurableApplicationContext start(Path db) {
        return SpringApplication.run(
                Application.class,
                "--patrol.database.path=" + db,
                "--server.port=0",
                "--patrol.demo.enabled=true",
                "--PATROL_DEMO_PASSWORD=RestartFixture123!",
                "--spring.main.banner-mode=off");
    }

    int port(ConfigurableApplicationContext c) {
        return ((ServletWebServerApplicationContext) c).getWebServer().getPort();
    }

    JsonNode request(int port, String method, String path, String body, String token, int expected)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response =
                http.send(
                        builder.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    @Test
    void routeAndSigningKeySurviveRealServiceRestart() throws Exception {
        Path db = Files.createTempDirectory("patrol-restart-").resolve("data/patrol.db");
        long routeId;
        String token;
        try (var first = start(db)) {
            int port = port(first);
            token =
                    request(
                                    port,
                                    "POST",
                                    "/api/v1/auth/login",
                                    "{\"username\":\"demo_operator\",\"password\":\"RestartFixture123!\"}",
                                    null,
                                    200)
                            .path("data")
                            .path("token")
                            .asText();
            routeId =
                    request(
                                    port,
                                    "POST",
                                    "/api/v1/routes",
                                    "{\"name\":\"重启保留路线\",\"points\":[{\"sequence\":0,\"x\":1.2,\"y\":3.4}]}",
                                    token,
                                    201)
                            .path("data")
                            .path("id")
                            .asLong();
            assertEquals(
                    1,
                    new JdbcTemplate(first.getBean(DataSource.class))
                            .queryForObject(
                                    "SELECT count(*) FROM patrol_route WHERE id=?",
                                    Integer.class,
                                    routeId));
        }
        assertTrue(Files.exists(db));
        assertTrue(Files.exists(db.resolveSibling("patrol.db.jwt-key")));
        try (var second = start(db)) {
            var route =
                    request(port(second), "GET", "/api/v1/routes/" + routeId, null, token, 200)
                            .path("data");
            assertEquals("重启保留路线", route.path("name").asText());
            assertEquals(1.2, route.path("points").get(0).path("x").asDouble());
            var jdbc = new JdbcTemplate(second.getBean(DataSource.class));
            assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM sys_user", Integer.class));
            assertEquals(
                    1, jdbc.queryForObject("SELECT count(*) FROM alarm_record", Integer.class));
            assertEquals(
                    4,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM control_log WHERE operation='DEMO_INIT'",
                            Integer.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM control_log WHERE operation='ROUTE_CREATE'",
                            Integer.class));
        }
    }
}
