package com.aitome.access;

import com.aitome.user.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 读取当前身份、角色与权限。不缓存到会话，保证角色/权限变更在下一次请求生效。 */
@Service
public class AccessService {
    public static final String REQUEST_ATTRIBUTE = AccessContext.class.getName();
    private final JdbcTemplate jdbc;

    public AccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 同一请求内只查询一次。 */
    public AccessContext current(HttpServletRequest request) {
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
        if (cached instanceof AccessContext context) return context;
        AccessContext context = resolve(request.getSession(false));
        request.setAttribute(REQUEST_ATTRIBUTE, context);
        return context;
    }

    public AccessContext resolve(HttpSession session) {
        Object id = session == null ? null : session.getAttribute(AuthController.USER_ID);
        if (!(id instanceof Long userId)) return AccessContext.guest();
        AccessContext context = forUser(userId);
        if (context == null || !context.active()) return AccessContext.guest(); // 用户不存在或账号已停用（FINNS-16）：按游客处理，受保护接口由拦截器返回“登录已失效”
        // 会话撤销（FINNS-15）：登录时记录的 epoch 与库中不一致，说明密码已被重置/修改或账号被撤销会话，按失效处理
        Object epoch = session.getAttribute(AuthController.AUTH_EPOCH);
        return epoch instanceof Long value && value == context.sessionEpoch() ? context : AccessContext.guest();
    }

    /** 用户不存在（会话失效）时返回 null。 */
    public AccessContext forUser(long userId) {
        List<AccessContext> rows = jdbc.query("""
                select u.id,u.display_name,u.session_epoch,u.account_status,r.id as role_id,r.code,r.name,r.system_kind
                from user_accounts u left join roles r on r.id=u.role_id where u.id=?""",
                (rs, n) -> new AccessContext(rs.getLong("id"), rs.getString("display_name"), (Long) rs.getObject("role_id"),
                        rs.getString("code"), rs.getString("name"), rs.getString("system_kind"), Set.of(), rs.getLong("session_epoch"), !"DISABLED".equals(rs.getString("account_status"))), userId);
        if (rows.isEmpty()) return null;
        AccessContext row = rows.get(0);
        if (row.roleId() == null) { // H2 开发库的历史用户在启动初始化前可能还没有角色，按注册默认角色处理
            RoleRow fallback = defaultRole();
            row = new AccessContext(row.userId(), row.displayName(), fallback.id(), fallback.code(), fallback.name(), fallback.systemKind(), Set.of(), row.sessionEpoch(), row.active());
        }
        return new AccessContext(row.userId(), row.displayName(), row.roleId(), row.roleCode(), row.roleName(), row.systemKind(), permissionsOf(row.roleId()), row.sessionEpoch(), row.active());
    }

    public Set<Permission> permissionsOf(long roleId) {
        EnumSet<Permission> result = EnumSet.noneOf(Permission.class);
        for (String code : jdbc.queryForList("select permission from role_permissions where role_id=?", String.class, roleId))
            Permission.fromCode(code).ifPresent(result::add);
        return Collections.unmodifiableSet(result);
    }

    public RoleRow defaultRole() {
        return jdbc.queryForObject("select " + RoleRow.COLUMNS + " from roles where system_kind=?", RoleRow::map, Role.KIND_DEFAULT);
    }
}
