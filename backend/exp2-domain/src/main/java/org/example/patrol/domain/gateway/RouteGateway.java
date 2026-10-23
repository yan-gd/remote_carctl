package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

public interface RouteGateway {
    PatrolRoute route(long id);

    PatrolRoute insertRoute(PatrolRoute route);

    void updateRoute(PatrolRoute route);

    void deleteRoute(long id);

    boolean routeReferenced(long id);

    Page<PatrolRoute> routes(int page, int size);
}
