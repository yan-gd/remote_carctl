package org.example.patrol.infrastructure.data;

import lombok.Data;

@Data
public class AlarmDO {
    private Long id;
    private String title;
    private String description;
    private String level;
    private String status;
    private String source;
    private Long occurredAt;
    private Long acknowledgedBy;
    private Long acknowledgedAt;
}
