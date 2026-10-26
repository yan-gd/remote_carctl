package org.example.patrol.app;

import org.example.patrol.client.ApiModels.UserView;
import org.example.patrol.domain.*;
import org.example.patrol.domain.gateway.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

/** Admin-only account lifecycle; deactivation preserves FK-linked audit records. */
@Service
public class UserManagementService {
    private final UserGateway users;
    private final PasswordGateway passwords;
    private final AuditService audit;
    private final Clock clock;

    public UserManagementService(UserGateway users, PasswordGateway passwords, AuditService audit, Clock clock) {
        this.users = users; this.passwords = passwords; this.audit = audit; this.clock = clock;
    }

    public record Create(String username, String password, String role, List<String> permissions) {
        @Override public String toString() { return "Create[credentials redacted]"; }
    }
    public record Change(String role, String password, List<String> permissions) {
        @Override public String toString() { return "Change[credentials redacted]"; }
    }

    public List<UserView> list(long actor) {
        administrator(actor);
        return users.allUsers().stream().map(Views::user).toList();
    }

    @Transactional
    public UserView create(long actorId, Create input) {
        var actor = administrator(actorId);
        if (input == null) throw BusinessException.invalid("请填写账号信息");
        User.validateCredentials(input.username(), input.password());
        Role role = role(input.role());
        Set<Permission> grants = grants(input.permissions(), role);
        User existing = users.allUsers().stream()
                .filter(u -> u.username().equalsIgnoreCase(input.username())).findFirst().orElse(null);
        User result;
        if (existing == null) {
            result = users.insertUser(new User(null, input.username(), passwords.hash(input.password()), role, clock.instant()));
        } else if (!existing.active() && !existing.admin()) {
            users.updateCredentials(existing.id(), passwords.hash(input.password()), role);
            result = existing;
        } else throw BusinessException.conflict("用户名已存在");
        users.setAccess(result.id(), grants, false, false);
        audit.success(actor, existing == null ? "USER_CREATE" : "USER_RESTORE", "user:" + result.id());
        return Views.user(users.userById(result.id()));
    }

    @Transactional
    public UserView update(long actorId, long id, Change input) {
        var actor = administrator(actorId);
        var target = editable(id);
        if (input == null) throw BusinessException.invalid("请填写权限信息");
        Role role = role(input.role());
        Set<Permission> grants = grants(input.permissions(), role);
        String hash = null;
        if (input.password() != null && !input.password().isBlank()) {
            User.validateCredentials(target.username(), input.password());
            hash = passwords.hash(input.password());
        }
        users.updateCredentials(id, hash, role);
        users.setAccess(id, grants, false, false);
        audit.success(actor, "USER_UPDATE", "user:" + id);
        return Views.user(users.userById(id));
    }

    @Transactional
    public void disable(long actorId, long id) {
        var actor = administrator(actorId);
        var target = editable(id);
        // Deliberately no hard DELETE: patrol_task and alarm_record reference this ID.
        users.setAccess(id, target.permissions(), false, true);
        audit.success(actor, "USER_DISABLE", "user:" + id);
    }

    private User editable(long id) {
        var user = users.userById(id);
        if (user == null) throw BusinessException.missing("账号");
        if (user.admin()) throw new BusinessException(BusinessException.Kind.FORBIDDEN,
                "ADMIN_PROTECTED", "管理员账号不可通过用户管理修改或删除");
        return user;
    }

    private User administrator(long id) {
        var actor = users.userById(id);
        if (actor == null || !actor.admin()) throw new BusinessException(
                BusinessException.Kind.FORBIDDEN, "FORBIDDEN", "仅系统管理员可以管理账号");
        return actor;
    }

    private static Role role(String value) {
        try { return Role.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException e) { throw BusinessException.invalid("请选择四种业务角色之一"); }
    }

    private static Set<Permission> grants(List<String> values, Role role) {
        if (values == null) return Permission.defaults(role);
        if (values.size() > Permission.values().length) throw BusinessException.invalid("权限列表有重复或非法值");
        var set = EnumSet.noneOf(Permission.class);
        for (String value : values) {
            try {
                if (!set.add(Permission.valueOf(value))) throw BusinessException.invalid("权限重复");
            } catch (IllegalArgumentException | NullPointerException e) { throw BusinessException.invalid("包含未知权限"); }
        }
        if ((set.contains(Permission.MOVE) || set.contains(Permission.NAVIGATE)
                || set.contains(Permission.MAP_EDIT)) && !set.contains(Permission.STOP))
            throw BusinessException.invalid("驾驶、建图或导航权限必须同时授予紧急停止权限");
        return set;
    }
}
