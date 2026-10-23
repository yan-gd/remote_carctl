package org.example.patrol.config;

import org.example.patrol.app.AuditService;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Explicit opt-in fixtures; never telemetry or real device events. */
@Component
@DependsOn("databaseInitializer")
@ConditionalOnProperty(name = "patrol.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    private final UserGateway users;
    private final PasswordGateway passwords;
    private final AlarmGateway alarms;
    private final AuditService audit;
    private final Clock clock;
    private final String password;

    public DemoDataInitializer(
            UserGateway users,
            PasswordGateway passwords,
            AlarmGateway alarms,
            AuditService audit,
            Clock clock,
            @Value("${PATROL_DEMO_PASSWORD:}") String password) {
        this.users = users;
        this.passwords = passwords;
        this.alarms = alarms;
        this.audit = audit;
        this.clock = clock;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        User.validateCredentials("demo_operator", password);
        for (Role role : Role.values()) {
            String name = "demo_" + role.name().toLowerCase(java.util.Locale.ROOT);
            if (users.userByName(name) == null) {
                var user =
                        users.insertUser(
                                new User(
                                        null,
                                        name,
                                        passwords.hash(password),
                                        role,
                                        clock.instant()));
                audit.success(user, "DEMO_INIT", "user:" + user.id());
            }
        }
        alarms.seedDemoAlarm();
    }
}
