package org.example.patrol.domain.gateway;

import org.example.patrol.domain.*;

public interface TaskGateway {
    PatrolTask task(long id);

    PatrolTask insertTask(PatrolTask task);

    void updateTask(PatrolTask task);

    void deleteTask(long id);

    Page<PatrolTask> tasks(int page, int size, TaskStatus status);
}
