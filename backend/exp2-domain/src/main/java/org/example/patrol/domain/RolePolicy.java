package org.example.patrol.domain;

public final class RolePolicy {
    private RolePolicy() {}

    public static void require(User user, Role role) {
        requireAny(user, role);
    }

    public static void require(User user, Permission permission) {
        if (user == null || !user.active())
            throw new BusinessException(BusinessException.Kind.UNAUTHORIZED, "UNAUTHORIZED", "用户不存在");
        if (!user.can(permission))
            throw new BusinessException(BusinessException.Kind.FORBIDDEN, "FORBIDDEN", "当前账号没有此操作权限");
    }

    public static void requireAny(User user, Role... roles) {
        if (user == null)
            throw new BusinessException(
                    BusinessException.Kind.UNAUTHORIZED, "UNAUTHORIZED", "用户不存在");
        if (user.admin()) return;
        for (Role role : roles) if (user.role() == role) return;
        throw new BusinessException(
                BusinessException.Kind.FORBIDDEN, "FORBIDDEN", "当前角色无权执行此操作");
    }
}
