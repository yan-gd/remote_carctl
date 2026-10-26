package org.example.patrol.config;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;

/** One-time explicit bootstrap: no default password, never rotates an existing credential. */
@Component
@DependsOn("databaseInitializer")
public class AdminInitializer implements ApplicationRunner {
    private final UserGateway users;
    private final PasswordGateway passwords;
    private final Clock clock;
    private final String bootstrapPassword;

    public AdminInitializer(UserGateway users, PasswordGateway passwords, Clock clock,
                            @Value("${PATROL_ADMIN_PASSWORD:}") String bootstrapPassword) {
        this.users=users; this.passwords=passwords; this.clock=clock;
        this.bootstrapPassword=bootstrapPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (bootstrapPassword.isEmpty()) return;
        User.validateCredentials("admin", bootstrapPassword);
        var existing = users.allUsers().stream()
                .filter(u -> u.username().equalsIgnoreCase("admin")).findFirst().orElse(null);
        if (existing != null) {
            if (!existing.admin() || !existing.active())
                throw new IllegalStateException("admin用户名已被占用，请先人工核查；不会自动提权");
            return;
        }
        var user = users.insertUser(new User(null, "admin", passwords.hash(bootstrapPassword),
                Role.OPERATOR, clock.instant()));
        users.setAccess(user.id(), EnumSet.allOf(Permission.class), true, false);
    }
}
