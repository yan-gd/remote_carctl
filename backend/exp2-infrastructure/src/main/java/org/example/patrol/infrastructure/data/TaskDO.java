package org.example.patrol.infrastructure.data;

import lombok.Data;

@Data
public class TaskDO {
    private Long id;
    private String name;
    private Long routeId;
    private Long creatorId;
    private Long plannedAt;
    private String status;
    private Long createdAt;
    private Long updatedAt;
}
