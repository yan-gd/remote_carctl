package org.example.patrol.domain;

/** Motion state reported by the robot bridge; UNKNOWN when nothing fresh or valid was reported. */
public enum MotionState {
    STOPPED,
    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT,
    UNKNOWN;

    public static MotionState of(String reported) {
        for (MotionState state : values()) if (state.name().equals(reported)) return state;
        return UNKNOWN;
    }
}
