package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

public interface UserGateway {
    User userById(long id);

    User userByName(String username);

    User insertUser(User user);
}
