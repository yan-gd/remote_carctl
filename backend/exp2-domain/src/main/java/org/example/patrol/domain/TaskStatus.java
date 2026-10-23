package org.example.patrol.domain;

/** Administrative planning states only; no device execution or completion. */
public enum TaskStatus {
    DRAFT,
    PLANNED,
    CANCELLED;

    public void checkTransition(TaskStatus next) {
        if (next == null) throw BusinessException.invalid("任务状态不能为空");
        if (this == CANCELLED && next != CANCELLED) throw BusinessException.conflict("已取消任务不能恢复");
    }
}
