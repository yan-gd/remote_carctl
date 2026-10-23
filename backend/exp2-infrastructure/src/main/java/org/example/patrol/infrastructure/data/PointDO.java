package org.example.patrol.infrastructure.data;

import lombok.Data;

@Data
public class PointDO {
    private Long id;
    private Long routeId;
    private Integer sequence;
    private Double x;
    private Double y;
    private Double yaw;
}
