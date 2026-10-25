package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

/** Inbound side of {@link RobotGateway}; called off the network thread, one event at a time. */
public interface RobotListener {
    void onLink(boolean connected);

    void onHeartbeat(RobotHeartbeat heartbeat);

    void onStatus(RobotTelemetry telemetry);

    void onAck(RobotAck ack);

    default void onNavigation(NavigationReport report) {}
}
