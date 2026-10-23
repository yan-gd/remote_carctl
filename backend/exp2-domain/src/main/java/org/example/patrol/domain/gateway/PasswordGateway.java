package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

public interface PasswordGateway {
    String hash(String raw);

    boolean matches(String raw, String encoded);
}
