package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

public interface TokenGateway {
    String issue(User user);

    long verify(String token);

    long expiresInSeconds();
}
