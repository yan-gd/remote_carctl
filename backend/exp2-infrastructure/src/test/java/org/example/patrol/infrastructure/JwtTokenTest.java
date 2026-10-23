package org.example.patrol.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.example.patrol.domain.*;
import org.example.patrol.infrastructure.security.JwtTokenGateway;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class JwtTokenTest {
    final byte[] bytes =
            "01234567890123456789012345678901".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final String secret = Base64.getEncoder().encodeToString(bytes);
    final Instant now = Instant.parse("2026-10-19T00:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final User user = new User(1L, "operator", "unused", Role.OPERATOR, now);

    @Test
    void validRoundTripAndExpiryBoundary() {
        var jwt = new JwtTokenGateway(secret, 60, clock);
        String token = jwt.issue(user);
        assertEquals(1, jwt.verify(token));
        assertEquals(60, jwt.expiresInSeconds());
        var later = new JwtTokenGateway(secret, 60, Clock.offset(clock, Duration.ofSeconds(61)));
        assertEquals(
                "TOKEN_EXPIRED",
                assertThrows(BusinessException.class, () -> later.verify(token)).getCode());
    }

    @Test
    void tamperedMalformedAndWrongIssuerAreRejected() {
        var jwt = new JwtTokenGateway(secret, 60, clock);
        assertThrows(BusinessException.class, () -> jwt.verify(jwt.issue(user) + "x"));
        assertThrows(BusinessException.class, () -> jwt.verify("bad.token"));
        String wrong =
                Jwts.builder()
                        .setIssuer("another-app")
                        .setSubject("1")
                        .setExpiration(Date.from(now.plusSeconds(60)))
                        .signWith(Keys.hmacShaKeyFor(bytes))
                        .compact();
        assertThrows(BusinessException.class, () -> jwt.verify(wrong));
    }

    @Test
    void rejectsMissingExpirationAndInvalidSubject() {
        var jwt = new JwtTokenGateway(secret, 60, clock);
        for (String subject : List.of("1", "-1", "not-a-number")) {
            String token =
                    Jwts.builder()
                            .setIssuer("patrol-management")
                            .setSubject(subject)
                            .signWith(Keys.hmacShaKeyFor(bytes))
                            .compact();
            assertThrows(BusinessException.class, () -> jwt.verify(token));
        }
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new JwtTokenGateway(secret, 0, clock));
        assertThrows(
                IllegalArgumentException.class, () -> new JwtTokenGateway(secret, 86401, clock));
        assertThrows(RuntimeException.class, () -> new JwtTokenGateway("c2hvcnQ=", 60, clock));
    }
}
