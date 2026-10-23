package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

import java.time.Instant;

/** Message link to the robot (MQTT in production). */
public interface RobotGateway {
    /** Whether the link to the broker is currently up. */
    boolean connected();

    /**
     * Hand one command to the broker and return the sequence number assigned to it. Throws
     * BusinessException UNAVAILABLE (MQTT_UNAVAILABLE) when the broker did not accept it.
     */
    long publish(RobotCommand command, Instant issuedAt);

    /** Register the consumer of inbound robot messages and link changes. */
    void listen(RobotListener listener);
}
