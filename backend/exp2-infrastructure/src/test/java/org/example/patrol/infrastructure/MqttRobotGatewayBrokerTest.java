package org.example.patrol.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.RobotListener;
import org.example.patrol.infrastructure.mqtt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;

/**
 * Round trip through a real broker, with a raw Paho client standing in for the robot bridge.
 * Opt-in because it needs a running broker: set MQTT_TEST_URL, e.g. tcp://127.0.0.1:1883.
 */
@EnabledIfEnvironmentVariable(named = "MQTT_TEST_URL", matches = "(tcp|ssl)://.+")
class MqttRobotGatewayBrokerTest {
    final String url = System.getenv("MQTT_TEST_URL");
    final String id = "IT-" + UUID.randomUUID().toString().substring(0, 8);
    final ObjectMapper json = new ObjectMapper();
    final BlockingQueue<Object> events = new LinkedBlockingQueue<>();

    final RobotListener recorder =
            new RobotListener() {
                public void onLink(boolean connected) {
                    events.add(connected);
                }

                public void onHeartbeat(RobotHeartbeat heartbeat) {
                    events.add(heartbeat);
                }

                public void onStatus(RobotTelemetry telemetry) {
                    events.add(telemetry);
                }

                public void onAck(RobotAck ack) {
                    events.add(ack);
                }

                public void onNavigation(NavigationReport report) {
                    events.add(report);
                }
            };

    String topic(String suffix) {
        return "robot/" + id + "/" + suffix;
    }

    <T> T next(Class<T> type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Object event = events.poll(100, TimeUnit.MILLISECONDS);
            if (type.isInstance(event)) return type.cast(event);
        }
        throw new AssertionError("no " + type.getSimpleName() + " within 10 s");
    }

    void publish(MqttClient robot, String suffix, String payload, boolean retained)
            throws MqttException {
        robot.publish(topic(suffix), payload.getBytes(UTF_8), 1, retained);
    }

    @Test
    void commandsStatusReceiptsAndWillTravelThroughTheBroker() throws Exception {
        var gateway =
                new MqttRobotGateway(
                        new MqttSettings(url, null, null, null, id, 5, 5, 500, 3000));
        gateway.listen(recorder);
        var robot = new MqttClient(url, "robot-" + id, new MemoryPersistence());
        var options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setWill(topic("heartbeat"), "{\"online\":false,\"reason\":\"LWT\"}".getBytes(UTF_8), 1, false);
        var controls = new LinkedBlockingQueue<String>();
        var navigation = new LinkedBlockingQueue<String>();
        robot.connect(options);
        robot.subscribe(
                topic("control"), 1, (t, m) -> controls.add(new String(m.getPayload(), UTF_8)));
        robot.subscribe(topic("navigation/command"),1,(t,m)-> {
            assertFalse(m.isRetained());
            navigation.add(new String(m.getPayload(),UTF_8));
        });
        try {
            // A retained status left on the broker before the backend subscribes is stale history:
            // the broker re-delivers it with RETAIN=1 and the gateway must ignore it.
            publish(robot, "status", "{\"motion_state\":\"LEFT\"}", true);
            gateway.start();
            assertEquals(Boolean.TRUE, next(Boolean.class));
            // The subscription completes asynchronously after the connection; repeat until seen.
            RobotHeartbeat heartbeat = null;
            for (int i = 0; i < 50 && heartbeat == null; i++) {
                publish(robot, "heartbeat", "{\"online\":true,\"chassis_connected\":true}", false);
                Object event = events.poll(200, TimeUnit.MILLISECONDS);
                assertFalse(event instanceof RobotTelemetry, "retained status was not ignored");
                if (event instanceof RobotHeartbeat h) heartbeat = h;
            }
            assertNotNull(heartbeat, "heartbeat never arrived");
            assertTrue(heartbeat.online());

            long seq = gateway.publish(RobotCommand.FORWARD, Instant.ofEpochMilli(1000));
            var control = json.readTree(controls.poll(10, TimeUnit.SECONDS));
            assertEquals("FORWARD", control.path("command").asText());
            assertEquals(seq, control.path("seq").asLong());
            assertEquals(gateway.session(), control.path("session").asText());
            assertEquals(id, control.path("robot_id").asText());
            assertTrue(gateway.publish(RobotCommand.STOP, Instant.now()) > seq);

            var order = new NavigationOrder("START","mission-test","map-test",
                    List.of(new NavigationOrder.Point(1.5,2,0)),2);
            long navigationSeq = gateway.publishNavigation(order,Instant.now());
            var navigationMessage = json.readTree(navigation.poll(10,TimeUnit.SECONDS));
            assertTrue(navigationSeq > seq);
            assertEquals(navigationSeq,navigationMessage.path("seq").asLong());
            assertEquals(gateway.session(),navigationMessage.path("session").asText());
            assertEquals("mission-test",navigationMessage.path("order").path("missionId").asText());
            assertEquals(1.5,navigationMessage.path("order").path("points").get(0).path("x").asDouble());
            publish(robot,"navigation/status","{\"robot_id\":\""+id+"\",\"ready\":true,\"state\":\"ACTIVE\",\"map_id\":\"map-test\",\"mission_id\":\"mission-test\",\"point_index\":0,\"total_points\":2}",false);
            var report = next(NavigationReport.class);
            assertTrue(report.ready());
            assertEquals("mission-test",report.missionId());
            assertEquals("ACTIVE",report.state());

            var relativeOrder=new NavigationOrder("START","nearby-test","relative-session",
                    List.of(new NavigationOrder.Point(2,1,0)),1,"relative");
            gateway.publishNavigation(relativeOrder,Instant.now());
            var relativeWire=json.readTree(navigation.poll(10,TimeUnit.SECONDS));
            assertEquals("relative",relativeWire.path("order").path("mode").asText());
            assertEquals(2,relativeWire.path("order").path("points").get(0).path("x").asDouble());
            publish(robot,"navigation/status","{\"robot_id\":\""+id+"\",\"mode\":\"relative\",\"frame\":\"odom\",\"ready\":true,\"state\":\"ACTIVE\",\"map_id\":\"relative-session\",\"mission_id\":\"nearby-test\"}",false);
            var nearbyReport=next(NavigationReport.class);
            assertEquals("relative",nearbyReport.mode());assertEquals("odom",nearbyReport.frame());
            assertEquals("nearby-test",nearbyReport.missionId());

            publish(
                    robot,
                    "status",
                    "{\"motion_state\":\"FORWARD\",\"battery_voltage\":25.0,\"position\":{\"x\":1,\"y\":2,\"yaw\":0}}",
                    false);
            var status = next(RobotTelemetry.class);
            assertEquals(MotionState.FORWARD, status.motionState());
            assertEquals(25.0, status.batteryVoltage());

            publish(
                    robot,
                    "control/ack",
                    "{\"seq\":" + seq + ",\"session\":\"" + gateway.session()
                            + "\",\"command\":\"FORWARD\",\"result\":\"APPLIED\"}",
                    false);
            assertEquals(seq, next(RobotAck.class).seq());

            // Drop the robot's connection without DISCONNECT: the broker must deliver its will.
            robot.disconnectForcibly(0, 1000, false);
            var will = next(RobotHeartbeat.class);
            assertFalse(will.online());
            assertEquals("LWT", will.reason());
        } finally {
            gateway.close();
            if (!robot.isConnected()) robot.connect();
            publish(robot, "status", "", true); // an empty retained message clears it
            robot.disconnect();
            robot.close();
        }
    }
}
