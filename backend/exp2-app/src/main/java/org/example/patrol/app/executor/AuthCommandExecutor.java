package org.example.patrol.app.executor;

import org.example.patrol.app.*;
import org.example.patrol.client.ApiModels.*;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class AuthCommandExecutor {
    private final UserGateway users;
    private final PasswordGateway passwords;
    private final TokenGateway tokens;
    private final AuditService audit;
    private final Clock clock;
    private final String dummyHash;

    public AuthCommandExecutor(
            UserGateway users,
            PasswordGateway passwords,
            TokenGateway tokens,
            AuditService audit,
            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = passwords.hash("unused-comparison-password");
    }

    @Transactional
    public UserView register(AuthCommand c) {
        User.validateCredentials(c.username(), c.password());
        if (users.userByNameIncludingDisabled(c.username()) != null) throw BusinessException.conflict("用户名已存在或已停用");
        var u =
                users.insertUser(
                        new User(
                                null,
                                c.username(),
                                passwords.hash(c.password()),
                                Role.VIEWER,
                                clock.instant()));
        audit.success(u, "REGISTER", "user:" + u.id());
        return Views.user(u);
    }

    public TokenView login(AuthCommand c) {
        User.validateCredentials(c.username(), c.password());
        var u = users.userByName(c.username());
        boolean correct = passwords.matches(c.password(), u == null ? dummyHash : u.passwordHash());
        if (u == null || !correct) {
            audit.failure(u == null ? null : u.id(), c.username(), "LOGIN", "auth", "LOGIN_FAILED");
            throw new BusinessException(
                    BusinessException.Kind.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
        }
        String token = tokens.issue(u);
        audit.success(u, "LOGIN", "auth");
        return new TokenView(token, "Bearer", tokens.expiresInSeconds(), Views.user(u));
    }
}
