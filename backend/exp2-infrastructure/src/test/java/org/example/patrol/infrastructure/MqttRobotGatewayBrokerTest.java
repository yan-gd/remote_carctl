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
        robot.connect(options);
        robot.subscribe(
                topic("control"), 1, (t, m) -> controls.add(new String(m.getPayload(), UTF_8)));
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
