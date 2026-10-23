package org.example.patrol.domain;

/**
 * Whitelisted chassis commands (requirement A.4.7). The backend never sends speeds; the robot-side
 * bridge maps each command to its own configured velocity.
 */
public enum RobotCommand {
    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT,
    STOP;

    public boolean isMotion() {
        return this != STOP;
    }

    /** Exact, case-sensitive match; anything else is INVALID_COMMAND. */
    public static RobotCommand parse(String value) {
        for (RobotCommand command : values()) if (command.name().equals(value)) return command;
        throw new BusinessException(
                BusinessException.Kind.INVALID,
                "INVALID_COMMAND",
                "命令须为FORWARD、BACKWARD、LEFT、RIGHT、STOP之一");
    }
}
