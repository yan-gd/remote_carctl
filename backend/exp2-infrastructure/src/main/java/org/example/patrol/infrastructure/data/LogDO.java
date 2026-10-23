package org.example.patrol.infrastructure.data;

import lombok.Data;

@Data
public class LogDO {
    private Long id;
    private Long actorId;
    private String actorName;
    private String operation;
    private String target;
    private String result;
    private Long occurredAt;
    private String error;
}
