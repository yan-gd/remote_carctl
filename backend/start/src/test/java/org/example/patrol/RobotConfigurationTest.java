package org.example.patrol;

import static org.junit.jupiter.api.Assertions.*;

import org.example.patrol.app.RobotSettings;
import org.example.patrol.config.RobotConfiguration;
import org.example.patrol.infrastructure.mqtt.*;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

class RobotConfigurationTest {
    final RobotConfiguration config = new RobotConfiguration();
    final RobotSettings robot =
            config.robotSettings("SCOUT-001", "Scout", "SCOUT MINI", 3000, 1500, 20.0, null);

    @Test
    void mqttCanBeSwitchedOff() {
        var gateway =
                config.robotGateway(robot, false, "tcp://127.0.0.1:1883", "", "", "", 10, 5, 5000, 1000);
        assertInstanceOf(DisabledRobotGateway.class, gateway);
        var lifecycle = config.robotLink(gateway);
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void enabledLinkStartsWithTheContextAndStopsBeforeIt() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var gateway =
                config.robotGateway(
                        robot, true, "tcp://127.0.0.1:" + port, "", "", "", 10, 1, 200, 500);
        assertInstanceOf(MqttRobotGateway.class, gateway);
        var lifecycle = config.robotLink(gateway);
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        assertFalse(gateway.connected());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
        ((MqttRobotGateway) gateway).close();
    }

    @Test
    void badPropertiesFailAtStartup() {
        assertThrows(
                IllegalArgumentException.class,
                () -> config.robotSettings("robot/#", "n", "m", 3000, 1500, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> config.robotSettings("SCOUT-001", "n", "m", 0, 1500, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        config.robotGateway(
                                robot, true, "localhost:1883", "", "", "", 10, 5, 5000, 1000));
    }
}
