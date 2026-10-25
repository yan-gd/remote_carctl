package org.example.patrol.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.patrol.domain.NavigationOrder;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NavigationMessagesTest {
    @Test void wireOrderHasBoundedExpiryAndSharedSessionSequence() throws Exception {
        var now=Instant.ofEpochMilli(1000);
        var order=new NavigationOrder("START","mission","map-id",List.of(new NavigationOrder.Point(1,2,0)),2);
        var n=new ObjectMapper().readTree(NavigationMessages.order(order,now,5,"session","SCOUT-001"));
        assertEquals(4000,n.path("expires_at").asLong());
        assertEquals(5,n.path("seq").asLong());
        assertEquals("session",n.path("session").asText());
        assertEquals("mission",n.path("order").path("missionId").asText());
        assertEquals(2,n.path("order").path("cycles").asInt());
        assertEquals("map",n.path("order").path("mode").asText());
    }
    @Test void reportRequiresRobotAndValidStateAndRejectsMalformedInput() {
        String report="{\"robot_id\":\"SCOUT-001\",\"ready\":true,\"state\":\"ACTIVE\",\"map_id\":\"map-id\",\"mission_id\":\"mission\",\"total_points\":2,\"point_index\":0,\"x\":1.5}";
        var parsed=NavigationMessages.report(report.getBytes(StandardCharsets.UTF_8),"SCOUT-001");
        assertNotNull(parsed); assertTrue(parsed.ready());assertEquals(1.5,parsed.x());
        assertEquals("mission",parsed.missionId());assertEquals(2,parsed.totalPoints());
        assertNull(NavigationMessages.report(report.getBytes(StandardCharsets.UTF_8),"OTHER"));
        assertNull(NavigationMessages.report(report.replace("ACTIVE","FAKE").getBytes(StandardCharsets.UTF_8),"SCOUT-001"));
        assertNull(NavigationMessages.report(new byte[16385],"SCOUT-001"));
        assertNull(NavigationMessages.report("bad json".getBytes(StandardCharsets.UTF_8),"SCOUT-001"));
    }

    @Test void relativeReportHasExplicitModeAndStableOdomReference() throws Exception {
        String message="{\"robot_id\":\"SCOUT-001\",\"ready\":true,\"state\":\"IDLE\",\"mode\":\"relative\",\"frame\":\"odom\",\"map_id\":\"relative-session\"}";
        var parsed=NavigationMessages.report(message.getBytes(StandardCharsets.UTF_8),"SCOUT-001");
        assertNotNull(parsed);assertEquals("relative",parsed.mode());assertEquals("relative-session",parsed.mapId());
        assertNull(NavigationMessages.report(message.replace("relative\",\"frame","unsupported\",\"frame").getBytes(StandardCharsets.UTF_8),"SCOUT-001"));
        assertNull(NavigationMessages.report(message.replace("\"odom\"","\"map\"").getBytes(StandardCharsets.UTF_8),"SCOUT-001"));
        var order=new NavigationOrder("START","mission",parsed.mapId(),List.of(new NavigationOrder.Point(2,1,0)),1,"relative");
        var json=new ObjectMapper().readTree(NavigationMessages.order(order,Instant.EPOCH,1,"backend","SCOUT-001"));
        assertEquals("relative",json.path("order").path("mode").asText());
        assertEquals(2,json.path("order").path("points").get(0).path("x").asInt());
    }
}
