package org.example.patrol.config;

import org.example.patrol.domain.gateway.TokenGateway;
import org.example.patrol.infrastructure.security.JwtTokenGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

@Configuration
public class IdentityConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public TokenGateway tokens(
            Path databasePath,
            Clock clock,
            @Value("${patrol.jwt.secret:}") String configured,
            @Value("${patrol.jwt.ttl-seconds:3600}") long ttl)
            throws java.io.IOException {
        String secret = configured;
        if (secret.isBlank()) {
            Path keyFile = databasePath.resolveSibling(databasePath.getFileName() + ".jwt-key");
            if (!Files.exists(keyFile)) {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                try {
                    Files.writeString(
                            keyFile,
                            Base64.getEncoder().encodeToString(bytes),
                            StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException ignored) {
                    /* another process initialized it */
                }
            }
            secret = Files.readString(keyFile).trim();
        }
        return new JwtTokenGateway(secret, ttl, clock);
    }
}
