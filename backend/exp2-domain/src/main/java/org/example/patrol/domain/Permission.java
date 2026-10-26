package org.example.patrol.domain;

import java.util.EnumSet;
import java.util.Set;

/** Explicit capabilities. User management is reserved to the bootstrap administrator. */
public enum Permission {
    MOVE, STOP, MAP_EDIT, NAVIGATE, ALARM_ACK, ALL_LOGS, PATROL_EDIT;

    public static Set<Permission> defaults(Role role) {
        return switch (role) {
            case OPERATOR -> EnumSet.of(MOVE, STOP, MAP_EDIT, NAVIGATE, PATROL_EDIT);
            case MONITOR -> EnumSet.of(STOP, ALARM_ACK);
            case MAINTAINER, VIEWER -> EnumSet.of(ALL_LOGS);
        };
    }
}
