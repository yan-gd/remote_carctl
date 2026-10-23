package org.example.patrol.infrastructure.mqtt;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;

import java.time.Instant;

/** Used when patrol.mqtt.enabled=false: the robot is never online and nothing is published. */
public class DisabledRobotGateway implements RobotGateway {
    @Override
    public boolean connected() {
        return false;
    }

    @Override
    public long publish(RobotCommand command, Instant issuedAt) {
        throw BusinessException.unavailable(
                "MQTT_UNAVAILABLE", "MQTT未启用（patrol.mqtt.enabled=false），命令未下发");
    }

    @Override
    public void listen(RobotListener listener) {
        // No link, so there will never be inbound messages.
    }
}
