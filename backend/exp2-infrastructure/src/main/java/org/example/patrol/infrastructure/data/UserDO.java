package org.example.patrol.infrastructure.data;

import lombok.Data;

@Data
public class UserDO {
    private Long id;
    private String username;
    private String passwordHash;
    private String role;
    private Long createdAt;
}
