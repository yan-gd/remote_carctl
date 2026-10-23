package org.example.patrol.domain;

public final class RolePolicy {
    private RolePolicy() {}

    public static void require(User user, Role role) {
        requireAny(user, role);
    }

    public static void requireAny(User user, Role... roles) {
        if (user == null)
            throw new BusinessException(
                    BusinessException.Kind.UNAUTHORIZED, "UNAUTHORIZED", "用户不存在");
        for (Role role : roles) if (user.role() == role) return;
        throw new BusinessException(
                BusinessException.Kind.FORBIDDEN, "FORBIDDEN", "当前角色无权执行此操作");
    }
}
