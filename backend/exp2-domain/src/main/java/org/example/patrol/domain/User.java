package org.example.patrol.domain;

import java.time.Instant;

public record User(Long id, String username, String passwordHash, Role role, Instant createdAt) {
    public static void validateCredentials(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,32}"))
            throw BusinessException.invalid("用户名须为3-32位字母、数字或下划线");
        if (password == null || !password.matches("[!-~]{8,72}"))
            throw BusinessException.invalid("密码须为8-72位非空格ASCII字符");
    }
}
