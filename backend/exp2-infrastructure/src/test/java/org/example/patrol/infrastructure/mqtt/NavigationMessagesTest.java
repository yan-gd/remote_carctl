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
}
