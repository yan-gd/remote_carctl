package org.example.patrol.infrastructure.data;

import lombok.Data;

@Data
public class UserAccessDO {
    private Long userId;
    private String permissions;
    private boolean admin;
    private boolean disabled;
}
