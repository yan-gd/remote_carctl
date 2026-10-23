package org.example.patrol.infrastructure.data;

import lombok.Data;

@Data
public class RouteDO {
    private Long id;
    private String name;
    private String description;
    private Long createdAt;
    private Long updatedAt;
}
