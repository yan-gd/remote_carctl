package org.example.patrol.domain;

/**
 * Liveness signal from the robot bridge. online=false arrives as the bridge's MQTT will (abnormal
 * disconnect) or on a clean shutdown; chassisConnected is null when the bridge cannot tell.
 */
public record RobotHeartbeat(boolean online, Boolean chassisConnected, String reason) {}
