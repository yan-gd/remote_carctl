package org.example.patrol.infrastructure.mqtt;

import java.util.UUID;

/**
 * Robot MQTT link settings (patrol.mqtt.*). Topics follow requirement table A-4:
 * robot/{id}/control, robot/{id}/status, robot/{id}/heartbeat, robot/{id}/control/ack.
 *
 * @param clientId blank means a random id, so two backend instances never kick each other off
 * @param reconnectIntervalMs fixed delay between connection attempts (requirement PER-007)
 * @param publishTimeoutMs how long a control command may wait for the broker's PUBACK
 */
public record MqttSettings(
        String brokerUrl,
        String clientId,
        String username,
        String password,
        String robotId,
        int keepAliveSeconds,
        int connectTimeoutSeconds,
        long reconnectIntervalMs,
        long publishTimeoutMs) {
    public MqttSettings {
        if (brokerUrl == null || !brokerUrl.matches("(tcp|ssl)://[^\\s/]+:\\d{1,5}"))
            throw new IllegalArgumentException("patrol.mqtt.broker-url 须为 tcp://主机:端口 或 ssl://主机:端口");
        if (robotId == null || !robotId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("patrol.robot.id 只能包含1-64位字母、数字、下划线或连字符");
        if (clientId == null || clientId.isBlank())
            clientId = "patrol-backend-" + UUID.randomUUID().toString().substring(0, 8);
        else if (!clientId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("patrol.mqtt.client-id 只能包含1-64位字母、数字、下划线或连字符");
        if (keepAliveSeconds < 1 || keepAliveSeconds > 300)
            throw new IllegalArgumentException("patrol.mqtt.keep-alive-seconds 须为1-300");
        if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 60)
            throw new IllegalArgumentException("patrol.mqtt.connect-timeout-seconds 须为1-60");
        if (reconnectIntervalMs < 100 || reconnectIntervalMs > 600_000)
            throw new IllegalArgumentException("patrol.mqtt.reconnect-interval-ms 须为100-600000");
        if (publishTimeoutMs < 50 || publishTimeoutMs > 30_000)
            throw new IllegalArgumentException("patrol.mqtt.publish-timeout-ms 须为50-30000");
        username = username == null || username.isBlank() ? null : username;
        password = password == null || password.isEmpty() ? null : password;
    }

    public String topic(String suffix) {
        return "robot/" + robotId + "/" + suffix;
    }

    @Override
    public String toString() {
        return "MqttSettings[brokerUrl="
                + brokerUrl
                + ", clientId="
                + clientId
                + ", username="
                + username
                + ", password="
                + (password == null ? "none" : "redacted")
                + ", robotId="
                + robotId
                + "]";
    }
}
