package org.example.patrol.domain;

/**
 * Robot-side receipt for one control message. APPLIED only means the bridge handed the command to
 * ROS (/cmd_vel), not that the chassis has finished moving.
 */
public record RobotAck(long seq, String command, String result, String reason) {}
