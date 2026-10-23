package org.example.patrol;

import static org.junit.jupiter.api.Assertions.*;

import org.example.Application;
import org.example.patrol.config.DatabaseConfiguration;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

@SpringBootTest(classes = Application.class)
class PersistenceTest {
    static final Path DB = tempDatabase();

    static Path tempDatabase() {
        try {
            return Files.createTempDirectory("patrol-test-").resolve("nested/test.db");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("patrol.database.path", () -> DB.toString());
    }

    @Autowired RouteGateway routes;
    @Autowired TaskGateway tasks;
    @Autowired UserGateway users;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager tx;
    @Autowired DataSourceInitializer initializer;

    PatrolRoute route(String name) {
        return new PatrolRoute(
                null,
                name,
                "test",
                List.of(new RoutePoint(0, 1, 2, null)),
                Instant.now(),
                Instant.now());
    }

    @Test
    void initializesCrudAndSurvivesRepeatedInitialization() {
        assertTrue(Files.exists(DB));
        var r = routes.insertRoute(route("持久化路线"));
        assertNotNull(r.id());
        assertEquals(1, r.points().size());
        routes.updateRoute(
                new PatrolRoute(
                        r.id(),
                        "更新",
                        null,
                        List.of(new RoutePoint(1, 3, 4, 0.0)),
                        r.createdAt(),
                        Instant.now()));
        initializer.afterPropertiesSet();
        assertEquals("更新", routes.route(r.id()).name());
        assertEquals(3, routes.route(r.id()).points().get(0).x());
        routes.deleteRoute(r.id());
        assertNull(routes.route(r.id()));
        assertEquals(
                0,
                new JdbcTemplate(source)
                        .queryForObject(
                                "SELECT count(*) FROM route_point WHERE route_id=?",
                                Integer.class,
                                r.id()));
    }

    @Test
    void enforcesForeignKeysOnEveryPooledConnection() throws Exception {
        try (var a = source.getConnection();
                var b = source.getConnection();
                var c = source.getConnection();
                var d = source.getConnection()) {
            for (var conn : List.of(a, b, c, d))
                try (var s = conn.createStatement()) {
                    try (var rs = s.executeQuery("PRAGMA foreign_keys")) {
                        assertTrue(rs.next());
                        assertEquals(1, rs.getInt(1));
                    }
                    assertThrows(
                            SQLException.class,
                            () ->
                                    s.executeUpdate(
                                            "INSERT INTO route_point(route_id,sequence,x,y)"
                                                + " VALUES(99999999,0,0,0)"));
                }
        }
    }

    @Test
    void rollsBackWholeRouteAndPoints() {
        long before = routes.routes(1, 100).total();
        assertThrows(
                IllegalStateException.class,
                () ->
                        new TransactionTemplate(tx)
                                .execute(
                                        status -> {
                                            routes.insertRoute(route("rollback"));
                                            throw new IllegalStateException("rollback");
                                        }));
        assertEquals(before, routes.routes(1, 100).total());
    }

    @Test
    void referencedRouteIsProtectedByDatabase() {
        var u =
                users.insertUser(
                        new User(
                                null,
                                "fk_test",
                                "not-a-login-fixture",
                                Role.VIEWER,
                                Instant.now()));
        var r = routes.insertRoute(route("fk"));
        var t =
                tasks.insertTask(
                        new PatrolTask(
                                null,
                                "fk",
                                r.id(),
                                u.id(),
                                null,
                                TaskStatus.DRAFT,
                                Instant.now(),
                                Instant.now()));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> routes.deleteRoute(r.id()));
        tasks.deleteTask(t.id());
        routes.deleteRoute(r.id());
    }

    @Test
    void resolvesStablePaths() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        assertEquals(
                DatabaseConfiguration.resolveDatabase("", root),
                DatabaseConfiguration.resolveDatabase("", root.resolve("start")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseConfiguration.resolveDatabase("relative.db", root));
    }
}
