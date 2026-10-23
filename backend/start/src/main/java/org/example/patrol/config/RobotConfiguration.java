package org.example.patrol.config;

import org.example.patrol.app.RobotSettings;
import org.example.patrol.domain.gateway.RobotGateway;
import org.example.patrol.infrastructure.mqtt.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Duration;

@Configuration
@EnableScheduling
public class RobotConfiguration {
    @Bean
    public RobotSettings robotSettings(
            @Value("${patrol.robot.id:SCOUT-001}") String id,
            @Value("${patrol.robot.name:SCOUT MINI Pro Kit}") String name,
            @Value("${patrol.robot.model:SCOUT MINI}") String model,
            @Value("${patrol.robot.heartbeat-timeout-ms:3000}") long heartbeatTimeoutMs,
            @Value("${patrol.robot.keepalive-window-ms:1500}") long keepaliveWindowMs,
            @Value("${patrol.robot.low-battery-pct:}") Double lowBatteryPct,
            @Value("${patrol.robot.low-battery-voltage:}") Double lowBatteryVoltage) {
        return new RobotSettings(
                id,
                name,
                model,
                Duration.ofMillis(heartbeatTimeoutMs),
                Duration.ofMillis(keepaliveWindowMs),
                lowBatteryPct,
                lowBatteryVoltage);
    }

    /** patrol.mqtt.enabled=false keeps the API usable with the robot permanently offline. */
    @Bean
    public RobotGateway robotGateway(
            RobotSettings robot,
            @Value("${patrol.mqtt.enabled:true}") boolean enabled,
            @Value("${patrol.mqtt.broker-url:tcp://127.0.0.1:1883}") String brokerUrl,
            @Value("${patrol.mqtt.client-id:}") String clientId,
            @Value("${patrol.mqtt.username:}") String username,
            @Value("${patrol.mqtt.password:}") String password,
            @Value("${patrol.mqtt.keep-alive-seconds:10}") int keepAliveSeconds,
            @Value("${patrol.mqtt.connect-timeout-seconds:5}") int connectTimeoutSeconds,
            @Value("${patrol.mqtt.reconnect-interval-ms:5000}") long reconnectIntervalMs,
            @Value("${patrol.mqtt.publish-timeout-ms:1000}") long publishTimeoutMs) {
        if (!enabled) return new DisabledRobotGateway();
        return new MqttRobotGateway(
                new MqttSettings(
                        brokerUrl,
                        clientId,
                        username,
                        password,
                        robot.id(),
                        keepAliveSeconds,
                        connectTimeoutSeconds,
                        reconnectIntervalMs,
                        publishTimeoutMs));
    }

    /**
     * Connects once every singleton exists, so RobotMonitor is already listening and the first
     * link event is not lost; disconnects before the database goes away on shutdown.
     */
    @Bean
    public SmartLifecycle robotLink(RobotGateway gateway) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                if (gateway instanceof MqttRobotGateway mqtt) mqtt.start();
                running = true;
            }

            @Override
            public void stop() {
                if (gateway instanceof MqttRobotGateway mqtt) mqtt.close();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
