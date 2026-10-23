package org.example.patrol.infrastructure.security;

import org.example.patrol.domain.gateway.PasswordGateway;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptPasswordGateway implements PasswordGateway {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String encoded) {
        return encoder.matches(raw, encoded);
    }
}
