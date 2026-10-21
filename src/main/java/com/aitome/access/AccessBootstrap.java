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
import java.util.Objects;

/**
 * 启动初始化（幂等）：
 * 1. H2 开发/测试库补齐状态行与四个种子角色（PostgreSQL 已由 V12 建好，角色表非空时不再写入，避免恢复被删除的角色）；
 * 2. 没有角色的历史用户设为注册默认角色；
 * 3. 若没有任何能管理角色的用户，把配置邮箱对应的账户设为管理员，同时成为唯一站长（契约 §4；FINNS-16 契约 §7）；
 * 4. 站长初始化与自愈（幂等）：站长指针为空时按配置邮箱的正常管理者或最早的正常管理者指定；
 *    指针用户持有站长角色；其他持有站长角色者（例如回退旧包期间被误分配）降为管理员。
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
            new SeedRole("ADMIN", "管理员", "管理用户、角色与权限", Role.KIND_ADMIN, 1000, Arrays.stream(Permission.values()).filter(p -> p != Permission.CONTENT_REVIEW).toList()));

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
        ensureOwnerRole();
        jdbc.update("update user_accounts set role_id=(select id from roles where system_kind='DEFAULT') where role_id is null");
        assignBootstrapAdmin();
        ensureOwner();
    }

    /** H2 没有迁移：补站长系统角色及全部权限；PostgreSQL 的 V14 已写入，这里不做任何事。 */
    private void ensureOwnerRole() {
        Integer existing = jdbc.queryForObject("select count(*) from roles where system_kind=?", Integer.class, Role.KIND_OWNER);
        if (existing != null && existing > 0) return;
        Integer clash = jdbc.queryForObject("select count(*) from roles where lower(name)=lower(?)", Integer.class, "站长");
        jdbc.update("insert into roles(code,name,description,system_kind,sort_order,created_at,updated_at) values(?,?,?,?,?,current_timestamp,current_timestamp)",
                "OWNER", clash != null && clash > 0 ? "站长（系统）" : "站长", "唯一站点所有者，管理管理员；仅通过专门入口转交", Role.KIND_OWNER, 1);
        Long id = jdbc.queryForObject("select id from roles where system_kind=?", Long.class, Role.KIND_OWNER);
        for (Permission p : Permission.values()) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", id, p.code());
    }

    private void ensureOwner() {
        RoleRow ownerRole = jdbc.queryForObject("select " + RoleRow.COLUMNS + " from roles where system_kind=?", RoleRow::map, Role.KIND_OWNER);
        RoleRow adminRole = jdbc.queryForObject("select " + RoleRow.COLUMNS + " from roles where system_kind=?", RoleRow::map, Role.KIND_ADMIN);
        Long pointer = jdbc.queryForObject("select owner_user_id from access_control_state where id=1", Long.class);
        boolean changed = false;
        if (pointer == null) {
            pointer = pickOwnerCandidate();
            if (pointer == null) { log.warn("FINNS 还没有可指定为站长的正常管理者；首位管理员注册并重启后生效"); return; }
            jdbc.update("update access_control_state set owner_user_id=?, owner_version=owner_version+1 where id=1", pointer);
            changed = true;
        }
        Map<String, Object> owner = jdbc.queryForMap("select u.id,u.display_name,r.code,r.name,u.role_id from user_accounts u left join roles r on r.id=u.role_id where u.id=?", pointer);
        if (!Objects.equals(owner.get("role_id") == null ? null : ((Number) owner.get("role_id")).longValue(), ownerRole.id())) {
            jdbc.update("update user_accounts set role_id=? where id=?", ownerRole.id(), pointer);
            audit.owner(null, "系统初始化", pointer, (String) owner.get("display_name"), (String) owner.get("code"), (String) owner.get("name"), ownerRole.code(), ownerRole.name(), "INIT", "初始化唯一站长", "SUCCESS", "站长已指定");
            changed = true;
        }
        for (Map<String, Object> stray : jdbc.queryForList("select u.id,u.display_name from user_accounts u where u.role_id=? and u.id<>? order by u.id", ownerRole.id(), pointer)) {
            long id = ((Number) stray.get("id")).longValue();
            jdbc.update("update user_accounts set role_id=? where id=?", adminRole.id(), id);
            audit.owner(null, "系统初始化", id, (String) stray.get("display_name"), ownerRole.code(), ownerRole.name(), adminRole.code(), adminRole.name(), "INIT", "站长只有一位，多余的站长角色已改为管理员", "SUCCESS", "已纠正");
            changed = true;
        }
        if (changed) jdbc.update("update access_control_state set version=version+1 where id=1");
    }

    /** 部署配置的站长邮箱（账号存在且正常即可，不要求已是管理者）优先，否则最早注册的正常管理者；没有则为空。 */
    private Long pickOwnerCandidate() {
        String managerSql = "select u.id from user_accounts u join role_permissions rp on rp.role_id=u.role_id and rp.permission=? where u.account_status='ACTIVE' ";
        if (!bootstrapAdminEmail.isEmpty()) {
            List<Long> configured = jdbc.queryForList("select id from user_accounts where account_status='ACTIVE' and lower(email)=lower(?) order by id", Long.class, bootstrapAdminEmail);
            if (!configured.isEmpty()) return configured.get(0);
        }
        List<Long> earliest = jdbc.queryForList(managerSql + "order by u.id limit 1", Long.class, Permission.ADMIN_ROLES.code());
        return earliest.isEmpty() ? null : earliest.get(0);
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
        RoleRow owner = jdbc.queryForObject("select " + RoleRow.COLUMNS + " from roles where system_kind='OWNER'", RoleRow::map);
        Map<String, Object> user = users.get(0);
        long userId = ((Number) user.get("id")).longValue();
        jdbc.update("update user_accounts set role_id=? where id=?", owner.id(), userId);
        jdbc.update("update access_control_state set owner_user_id=?, owner_version=owner_version+1, version=version+1 where id=1", userId);
        audit.assign(null, "系统初始化", userId, (String) user.get("display_name"),
                (String) user.get("code"), (String) user.get("name"), owner.code(), owner.name(), "按部署配置指定首位管理员（同时为唯一站长）");
        log.info("已按部署配置把用户 {} 设为首位管理员和站长", userId);
    }

    record SeedRole(String code, String name, String description, String systemKind, int sortOrder, List<Permission> permissions) {}
}
