package org.example.patrol;

import org.example.Application;
import org.example.patrol.domain.Role;
import org.example.patrol.domain.User;
import org.example.patrol.domain.gateway.TokenGateway;
import org.example.patrol.domain.gateway.UserGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Camera key is absent, so snapshot must return JSON 503 for every Accept value. */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
class CameraSnapshotDisabledTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var db = PersistenceTest.tempDatabase();
        registry.add("patrol.database.path", db::toString);
        registry.add("patrol.mqtt.enabled", () -> false);
        registry.add("PATROL_CAMERA_DEVICE_KEY", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired UserGateway users;
    @Autowired TokenGateway tokens;

    @Test
    void snapshotDisabledIsJson503ForJpegAndJsonAccept() throws Exception {
        var name = "snapshot_disabled_viewer";
        var user = users.userByName(name);
        if (user == null) {
            users.insertUser(new User(null, name, "unused-hash", Role.VIEWER, Instant.now()));
            user = users.userByName(name);
        }
        var jwt = "Bearer " + tokens.issue(user);
        for (var accept : new MediaType[] {MediaType.IMAGE_JPEG, MediaType.APPLICATION_JSON}) {
            mvc.perform(get("/api/v1/camera/snapshot").header("Authorization", jwt).accept(accept))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("CAMERA_DISABLED"));
        }
    }
}
