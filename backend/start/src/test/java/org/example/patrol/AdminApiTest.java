package org.example.patrol;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import org.example.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class AdminApiTest {
    static final Path DB = PersistenceTest.tempDatabase();
    static final String PASSWORD = "AdminTestPassword123!";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("patrol.database.path", () -> DB.toString());
        registry.add("PATROL_ADMIN_PASSWORD", () -> PASSWORD);
        registry.add("patrol.mqtt.enabled", () -> false);
        registry.add("patrol.jwt.secret", () -> Base64.getEncoder().encodeToString(
                "admin-api-test-signing-key-32-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    JsonNode call(MockHttpServletRequestBuilder request, Object body, String token, int code) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        String result = mvc.perform(request).andExpect(status().is(code)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(result);
    }

    String login(String name, String password) throws Exception {
        return call(post("/api/v1/auth/login"), Map.of("username", name, "password", password), null, 200)
                .path("data").path("token").asText();
    }

    @Test
    void bootstrapAdminAndCustomPermissionsAreEnforcedByBackend() throws Exception {
        String admin = login("admin", PASSWORD);
        var list = call(get("/api/v1/admin/users"), null, admin, 200).path("data");
        assertEquals("ADMIN", list.get(0).path("role").asText());
        assertFalse(list.toString().contains(PASSWORD));

        var created = call(post("/api/v1/admin/users"),
                Map.of("username", "custom_viewer", "password", "ViewerPassword123!",
                        "role", "VIEWER", "permissions", List.of("STOP")), admin, 201).path("data");
        long id = created.path("id").asLong();
        assertEquals("VIEWER", created.path("role").asText());
        String viewer = login("custom_viewer", "ViewerPassword123!");
        call(get("/api/v1/admin/users"), null, viewer, 403);
        call(post("/api/v1/robot/control"), Map.of("command", "FORWARD"), viewer, 403);

        call(put("/api/v1/admin/users/" + id),
                Map.of("role", "VIEWER", "permissions", List.of("MOVE", "STOP")), admin, 200);
        // Role is still VIEWER, but the server recognizes the explicit MOVE grant.
        call(post("/api/v1/robot/control"), Map.of("command", "FORWARD"), viewer, 503);
        call(put("/api/v1/admin/users/" + id),
                Map.of("role", "VIEWER", "permissions", List.of("MOVE")), admin, 400);
        call(delete("/api/v1/admin/users/" + id), null, admin, 200);
        call(get("/api/v1/auth/me"), null, viewer, 401);
        call(delete("/api/v1/admin/users/" + list.get(0).path("id").asLong()), null, admin, 403);

        // Recreating a disabled name reactivates the original ID and resets its credentials.
        var restored = call(post("/api/v1/admin/users"),
                Map.of("username", "custom_viewer", "password", "NewViewerPassword123!",
                        "role", "MONITOR", "permissions", List.of("STOP", "ALARM_ACK")), admin, 201).path("data");
        assertEquals(id, restored.path("id").asLong());
        login("custom_viewer", "NewViewerPassword123!");
    }
}
