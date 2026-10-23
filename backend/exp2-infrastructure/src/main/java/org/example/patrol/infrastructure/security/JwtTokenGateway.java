package org.example.patrol.infrastructure.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.TokenGateway;

import java.time.Clock;
import java.util.*;

import javax.crypto.SecretKey;

public class JwtTokenGateway implements TokenGateway {
    private final SecretKey key;
    private final Clock clock;
    private final long ttl;
    private final JwtParser parser;

    public JwtTokenGateway(String base64Secret, long ttl, Clock clock) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        if (ttl < 1 || ttl > 86400) throw new IllegalArgumentException("JWT有效期须为1-86400秒");
        this.ttl = ttl;
        this.clock = clock;
        this.parser =
                Jwts.parserBuilder()
                        .setSigningKey(key)
                        .requireIssuer("patrol-management")
                        .setClock(() -> Date.from(clock.instant()))
                        .build();
    }

    public String issue(User user) {
        return Jwts.builder()
                .setIssuer("patrol-management")
                .setSubject(user.id().toString())
                .setIssuedAt(Date.from(clock.instant()))
                .setExpiration(Date.from(clock.instant().plusSeconds(ttl)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public long verify(String token) {
        try {
            var claims = parser.parseClaimsJws(token).getBody();
            long id = Long.parseLong(claims.getSubject());
            if (id <= 0 || claims.getExpiration() == null || claims.getIssuedAt() == null)
                throw new IllegalArgumentException("claims");
            return id;
        } catch (ExpiredJwtException e) {
            throw new BusinessException(
                    BusinessException.Kind.UNAUTHORIZED, "TOKEN_EXPIRED", "登录凭证已过期");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(
                    BusinessException.Kind.UNAUTHORIZED, "UNAUTHORIZED", "登录凭证无效");
        }
    }

    public long expiresInSeconds() {
        return ttl;
    }
}
