package org.example.patrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.Application;
import org.example.patrol.app.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.example.patrol.domain.Role;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("camera-navigation-test")
@Import(CameraNavigationApiTest.LinkConfig.class)
class CameraNavigationApiTest {
    static final String KEY="isolated-test-device-key-32-characters";
    static final byte[] JPEG={(byte)255,(byte)216,1,2,(byte)255,(byte)217};
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        var db=PersistenceTest.tempDatabase();
        r.add("patrol.database.path",db::toString);
        r.add("patrol.mqtt.enabled",()->false);
        r.add("PATROL_CAMERA_DEVICE_KEY",()->KEY);
        r.add("PATROL_NAVIGATION_ENABLED",()->true);
    }
    @TestConfiguration @Profile("camera-navigation-test") static class LinkConfig {
        @Bean @Primary Link cameraNavigationLink() { return new Link(); }
    }
    static class Link implements RobotGateway {
        final List<Object> sent=new ArrayList<>();
        public boolean connected() { return true; }
        public void listen(RobotListener listener) {}
        public long publish(RobotCommand command,Instant now) { sent.add(command);return sent.size(); }
        public long publishNavigation(NavigationOrder order,Instant now) { sent.add(order);return sent.size(); }
    }
    @Autowired MockMvc mvc;
    @Autowired CameraRelay relay;
    @Autowired RobotMonitor monitor;
    @Autowired Link link;
    @Autowired UserGateway users;
    @Autowired TokenGateway tokens;
    @Autowired ObjectMapper json;

    String token(Role role) {
        var name="new_api_"+role;
        var user=users.userByName(name);
        if(user==null) {
            users.insertUser(new User(null,name,"unused-hash",role,Instant.now()));
            user=users.userByName(name);
        }
        return "Bearer "+tokens.issue(user);
    }

    @Test void mediaReadsNeedJwtAndDeviceWritesNeedSeparateKey() throws Exception {
        for(var suffix:List.of("status","snapshot","stream"))
            mvc.perform(get("/api/v1/camera/"+suffix)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/device/camera/frame").contentType("image/jpeg").content(JPEG))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("DEVICE_UNAUTHORIZED"));
        mvc.perform(put("/api/v1/device/camera/frame").header("Authorization",token(Role.OPERATOR))
            .contentType("image/jpeg").content(JPEG)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/device/camera/frame").header("X-Device-Key",KEY)
            .contentType("image/jpeg").content(JPEG)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/camera/status").header("Authorization",token(Role.VIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.persistence").value("NONE"));
        mvc.perform(put("/api/v1/device/camera/frame").header("X-Device-Key",KEY)
            .contentType("image/jpeg").content(new byte[CameraRelay.MAX_FRAME_BYTES+1]))
            .andExpect(status().isBadRequest());
        assertEquals(0,relay.status().viewers());
    }

    @Test void snapshotReceivesOnlyANewFrameThroughActualIngressAndReleasesSubscription() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        var jwt=token(Role.VIEWER);
        try {
            relay.accept(JPEG); // An old image is not a snapshot cache.
            var response=executor.submit(()->mvc.perform(get("/api/v1/camera/snapshot")
                .header("Authorization",jwt)).andReturn().getResponse());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(relay.status().viewers()==0 && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals(1,relay.status().viewers());
            assertFalse(response.isDone());
            mvc.perform(put("/api/v1/device/camera/frame").header("X-Device-Key",KEY)
                .contentType("image/jpeg").content(JPEG)).andExpect(status().isOk());
            var snapshot=response.get(3,TimeUnit.SECONDS);
            assertEquals(200,snapshot.getStatus());
            assertArrayEquals(JPEG,snapshot.getContentAsByteArray());
            assertTrue(snapshot.getHeader("Cache-Control").contains("no-store"));
            assertEquals("no",snapshot.getHeader("X-Accel-Buffering"));
            assertEquals(0,relay.status().viewers());
        } finally { executor.shutdownNow(); }
    }

    @Test void navigationValidatesRolesReadinessMapAndLeaseThroughSecurityChain() throws Exception {
        var request=Map.of("mapId","map-a","cycles",1,"points",List.of(Map.of("x",1,"y",2,"yaw",0)));
        var body=json.writeValueAsBytes(request);
        mvc.perform(get("/api/v1/navigation")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/navigation/missions").header("Authorization",token(Role.VIEWER))
            .contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/navigation/missions").header("Authorization",token(Role.OPERATOR))
            .contentType("application/json").content(body)).andExpect(status().isConflict());
        monitor.onLink(true);monitor.onHeartbeat(new RobotHeartbeat(true,true,null));
        monitor.onNavigation(new NavigationReport("SCOUT-001",true,"READY","map-a","map","IDLE",null,0,0,0.0,0.0,0.0));
        var response=mvc.perform(post("/api/v1/navigation/missions").header("Authorization",token(Role.OPERATOR))
            .contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse();
        var id=json.readTree(response.getContentAsString()).path("data").path("missionId").asText();
        assertFalse(id.isBlank());
        mvc.perform(get("/api/v1/navigation").header("Authorization",token(Role.VIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.missionId").value(id))
            .andExpect(jsonPath("$.data.active").value(true));
        mvc.perform(post("/api/v1/navigation/cancel").header("Authorization",token(Role.MONITOR)))
            .andExpect(status().isOk());
        assertEquals(RobotCommand.STOP,link.sent.get(link.sent.size()-2));
        assertEquals("CANCEL",((NavigationOrder)link.sent.get(link.sent.size()-1)).action());
    }
}
