package org.example.patrol.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.RobotListener;
import org.example.patrol.infrastructure.mqtt.*;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.concurrent.*;

/** Behaviour without a reachable broker; the real round trip is in MqttRobotGatewayBrokerTest. */
class MqttRobotGatewayTest {
    static MqttSettings settings(String url) {
        return new MqttSettings(url, null, null, null, "SCOUT-001", 10, 2, 200, 500);
    }

    static int closedPort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void settingsAreValidatedAndThePasswordIsNeverPrinted() {
        var s =
                new MqttSettings(
                        "tcp://127.0.0.1:1883", "", "user", "secret", "SCOUT-001", 10, 5, 5000, 1000);
        assertTrue(s.clientId().matches("patrol-backend-[0-9a-f]{8}"));
        assertFalse(s.toString().contains("secret"));
        assertEquals("robot/SCOUT-001/control/ack", s.topic("control/ack"));
        assertNull(new MqttSettings("ssl://broker:8883", "id", " ", "", "R", 1, 1, 100, 50).username());
        String[] badUrls = {null, "http://broker:1883", "tcp://broker", "tcp://bro ker:1883"};
        for (String url : badUrls)
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MqttSettings(url, null, null, null, "R", 10, 5, 5000, 1000));
        for (String id : new String[] {"a/b", "+", "#", ""})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MqttSettings("tcp://h:1", null, null, null, id, 10, 5, 5000, 1000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MqttSettings("tcp://h:1", "bad id", null, null, "R", 10, 5, 5000, 1000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MqttSettings("tcp://h:1", null, null, null, "R", 0, 5, 5000, 1000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MqttSettings("tcp://h:1", null, null, null, "R", 10, 0, 5000, 1000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MqttSettings("tcp://h:1", null, null, null, "R", 10, 5, 99, 1000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MqttSettings("tcp://h:1", null, null, null, "R", 10, 5, 5000, 10));
    }

    @Test
    void unreachableBrokerMeansNothingIsSentAndTheLinkIsReportedDown() throws Exception {
        var gateway = new MqttRobotGateway(settings("tcp://127.0.0.1:" + closedPort()));
        var links = new LinkedBlockingQueue<Boolean>();
        gateway.listen(
                new RobotListener() {
                    public void onLink(boolean connected) {
                        links.add(connected);
                    }

                    public void onHeartbeat(RobotHeartbeat heartbeat) {}

                    public void onStatus(RobotTelemetry telemetry) {}

                    public void onAck(RobotAck ack) {}
                });
        try {
            assertFalse(gateway.connected());
            assertEquals(
                    "MQTT_UNAVAILABLE",
                    assertThrows(
                                    BusinessException.class,
                                    () -> gateway.publish(RobotCommand.STOP, Instant.now()))
                            .getCode());
            gateway.start();
            gateway.start();
            assertEquals(Boolean.FALSE, links.poll(10, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, links.poll(10, TimeUnit.SECONDS), "it keeps retrying");
            assertNotNull(gateway.session());
        } finally {
            gateway.close();
            gateway.close();
        }
        assertFalse(gateway.connected());
        gateway.start(); // a closed gateway never reconnects
        assertFalse(gateway.connected());
    }

    @Test
    void disabledGatewayIsAlwaysOffline() {
        var gateway = new DisabledRobotGateway();
        gateway.listen(null);
        assertFalse(gateway.connected());
        var e =
                assertThrows(
                        BusinessException.class,
                        () -> gateway.publish(RobotCommand.STOP, Instant.now()));
        assertEquals(BusinessException.Kind.UNAVAILABLE, e.getKind());
        assertEquals("MQTT_UNAVAILABLE", e.getCode());
    }
}
