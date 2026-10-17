package com.aitome.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 启动初始化（幂等）：
 * 1. H2 开发/测试库补齐状态行与四个种子角色（PostgreSQL 已由 V12 建好，角色表非空时不再写入，避免恢复被删除的角色）；
 * 2. 没有角色的历史用户设为注册默认角色；
 * 3. 若没有任何能管理角色的用户，把配置邮箱对应的账户设为管理员（契约 §4）。
 */
@Component
public class AccessBootstrap {
    private static final Logger log = LoggerFactory.getLogger(AccessBootstrap.class);
    static final List<SeedRole> SEED = List.of(
            new SeedRole("RESTRICTED", "受限成员", "可浏览，暂停发布与互动", null, 10,
                    List.of(Permission.CONTENT_PUBLIC, Permission.PROFILE_MANAGE, Permission.SELECTION_MANAGE)),
            new SeedRole("MEMBER", "普通成员", "注册用户的默认角色", Role.KIND_DEFAULT, 20,
                    List.of(Permission.CONTENT_PUBLIC, Permission.PROFILE_MANAGE, Permission.SELECTION_MANAGE, Permission.POST_CREATE,
                            Permission.POST_INTERACT, Permission.COLLECTION_MANAGE, Permission.AI_USE)),
            new SeedRole("TRUSTED", "受信成员", "可阅读内部受限专题", null, 30,
                    List.of(Permission.CONTENT_PUBLIC, Permission.PROFILE_MANAGE, Permission.SELECTION_MANAGE, Permission.POST_CREATE,
                            Permission.POST_INTERACT, Permission.COLLECTION_MANAGE, Permission.AI_USE, Permission.CONTENT_RESTRICTED)),
            new SeedRole("ADMIN", "管理员", "管理用户、角色与权限", Role.KIND_ADMIN, 1000, Arrays.asList(Permission.values())));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccessAuditWriter audit;
    private final String bootstrapAdminEmail;

    public AccessBootstrap(JdbcTemplate jdbc, TransactionTemplate tx, AccessAuditWriter audit,
                           @Value("${finns.access.bootstrap-admin-email:}") String bootstrapAdminEmail) {
        this.jdbc = jdbc; this.tx = tx; this.audit = audit; this.bootstrapAdminEmail = bootstrapAdminEmail == null ? "" : bootstrapAdminEmail.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() { tx.executeWithoutResult(status -> run()); }

    void run() {
        Integer state = jdbc.queryForObject("select count(*) from access_control_state where id=1", Integer.class);
        if (state == null || state == 0) jdbc.update("insert into access_control_state(id,version) values(1,1)");
        Integer roles = jdbc.queryForObject("select count(*) from roles", Integer.class);
        if (roles == null || roles == 0) for (SeedRole seed : SEED) insertSeed(seed);
        jdbc.update("update user_accounts set role_id=(select id from roles where system_kind='DEFAULT') where role_id is null");
        assignBootstrapAdmin();
    }

    private void insertSeed(SeedRole seed) {
        jdbc.update("insert into roles(code,name,description,system_kind,sort_order,created_at,updated_at) values(?,?,?,?,?,current_timestamp,current_timestamp)",
                seed.code(), seed.name(), seed.description(), seed.systemKind(), seed.sortOrder());
        Long id = jdbc.queryForObject("select id from roles where code=?", Long.class, seed.code());
        for (Permission p : seed.permissions()) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", id, p.code());
    }

    private void assignBootstrapAdmin() {
        Integer managers = jdbc.queryForObject("select count(*) from user_accounts u join role_permissions rp on rp.role_id=u.role_id where rp.permission=?",
                Integer.class, Permission.ADMIN_ROLES.code());
        if (managers != null && managers > 0) return;
        if (bootstrapAdminEmail.isEmpty()) { log.warn("FINNS 没有能管理角色的用户，且未配置 finns.access.bootstrap-admin-email（FINNS_BOOTSTRAP_ADMIN_EMAIL）"); return; }
        List<Map<String, Object>> users = jdbc.queryForList("select u.id,u.display_name,r.code,r.name from user_accounts u left join roles r on r.id=u.role_id where lower(u.email)=lower(?)", bootstrapAdminEmail);
        if (users.isEmpty()) { log.warn("首位管理员邮箱尚无对应账户，注册后重启即可生效"); return; }
        RoleRow admin = jdbc.queryForObject("select " + RoleRow.COLUMNS + " from roles where system_kind='ADMIN'", RoleRow::map);
        Map<String, Object> user = users.get(0);
        long userId = ((Number) user.get("id")).longValue();
        jdbc.update("update user_accounts set role_id=? where id=?", admin.id(), userId);
        jdbc.update("update access_control_state set version=version+1 where id=1");
        audit.assign(null, "系统初始化", userId, (String) user.get("display_name"),
                (String) user.get("code"), (String) user.get("name"), admin.code(), admin.name(), "按部署配置指定首位管理员");
        log.info("已按部署配置把用户 {} 设为管理员", userId);
    }

    record SeedRole(String code, String name, String description, String systemKind, int sortOrder, List<Permission> permissions) {}
}
