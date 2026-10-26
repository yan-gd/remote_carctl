package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;
import java.util.List;
import java.util.Set;

public interface UserGateway {
    User userById(long id);

    User userByName(String username);

    User userByNameIncludingDisabled(String username);

    User insertUser(User user);

    List<User> allUsers();

    void setAccess(long id, Set<Permission> permissions, boolean admin, boolean disabled);

    void updateCredentials(long id, String passwordHash, Role role);

    User userIncludingDisabled(long id);
}
