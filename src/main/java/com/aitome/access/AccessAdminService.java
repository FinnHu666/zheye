package com.aitome.access;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户角色分配、角色权限修改、角色新增/改名/删除与操作记录查询（契约 §5）。
 * 所有写操作在一个事务内锁定 access_control_state 行，比对版本并保证至少保留一位能管理角色的用户。
 */
@Service
public class AccessAdminService {
    public static final int MAX_ROLES = 12;
    // 角色为空的用户（例如回退到 0.6.0 期间注册的用户）按注册默认角色处理，下次启动时由 AccessBootstrap 补齐。
    private final JdbcTemplate jdbc;
    private final AccessAuditWriter audit;
    private final ZoneId auditZone;

    public AccessAdminService(JdbcTemplate jdbc, AccessAuditWriter audit, @Value("${finns.access.audit-zone:Asia/Shanghai}") String auditZone) {
        this.jdbc = jdbc; this.audit = audit; this.auditZone = ZoneId.of(auditZone);
    }

    // —— 用户 ——

    public UserPage users(String q, String roleCode, int page, int size) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (query.length() > 80) throw bad("搜索内容最多 80 个字");
        int safePage = Math.max(0, Math.min(page, 10_000)), safeSize = Math.max(1, Math.min(size, 50));
        String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String role = roleCode == null ? "" : roleCode.trim();
        String where = " where (?='' or lower(u.display_name) like ? escape '\\' or lower(u.email) like ? escape '\\') and (?='' or r.code=?)";
        Object[] args = {query, like, like, role, role};
        List<UserItem> items = jdbc.query("select u.id,u.display_name,u.email,u.created_at,r.code,r.name, exists(select 1 from role_permissions rp where rp.role_id=u.role_id and rp.permission='admin.roles') as can_manage " +
                        "from user_accounts u join roles r on r.id=coalesce(u.role_id,(select d.id from roles d where d.system_kind='DEFAULT'))" + where + " order by u.created_at asc,u.id asc limit ? offset ?",
                (rs, n) -> new UserItem(rs.getLong("id"), rs.getString("display_name"), rs.getString("email"), new RoleRef(rs.getString("code"), rs.getString("name")),
                        rs.getBoolean("can_manage"), rs.getTimestamp("created_at").toInstant()),
                concat(args, safeSize, (long) safePage * safeSize));
        Long total = jdbc.queryForObject("select count(*) from user_accounts u join roles r on r.id=coalesce(u.role_id,(select d.id from roles d where d.system_kind='DEFAULT'))" + where, Long.class, args);
        return new UserPage(items, safePage, safeSize, total == null ? 0 : total, managerCount(currentPermissions(), userCountsByRole()));
    }

    @Transactional
    public UserItem assignRole(AccessContext operator, long userId, String roleCode, String expectedRoleCode, String note) {
        lockVersion();
        checkNote(note);
        Map<String, Object> user = jdbc.queryForList("select u.id,u.display_name,r.id as role_id,r.code,r.name from user_accounts u join roles r on r.id=coalesce(u.role_id,(select d.id from roles d where d.system_kind='DEFAULT')) where u.id=?", userId)
                .stream().findFirst().orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "用户不存在"));
        RoleRow target = roleByCode(roleCode).orElseThrow(() -> bad("目标角色不存在，可能已被删除，请刷新后重试"));
        String currentCode = (String) user.get("code");
        if (expectedRoleCode != null && !expectedRoleCode.isBlank() && !expectedRoleCode.equals(currentCode))
            throw new ApiProblem(HttpStatus.CONFLICT, "该用户角色已被修改为「" + user.get("name") + "」，请核对后重试");
        if (!target.code().equals(currentCode)) {
            Map<Long, Long> counts = userCountsByRole();
            long currentRoleId = ((Number) user.get("role_id")).longValue();
            counts.merge(currentRoleId, -1L, Long::sum);
            counts.merge(target.id(), 1L, Long::sum);
            if (managerCount(currentPermissions(), counts) < 1)
                throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "这是最后一位能管理角色的用户，不能改为没有管理权限的角色；请先指定另一位管理员");
            jdbc.update("update user_accounts set role_id=? where id=?", target.id(), userId);
            bumpVersion();
            audit.assign(operator.userId(), operator.displayName(), userId, (String) user.get("display_name"), currentCode, (String) user.get("name"), target.code(), target.name(), note);
        }
        return jdbc.queryForObject("select u.id,u.display_name,u.email,u.created_at,r.code,r.name, exists(select 1 from role_permissions rp where rp.role_id=u.role_id and rp.permission='admin.roles') as can_manage from user_accounts u join roles r on r.id=coalesce(u.role_id,(select d.id from roles d where d.system_kind='DEFAULT')) where u.id=?",
                (rs, n) -> new UserItem(rs.getLong("id"), rs.getString("display_name"), rs.getString("email"), new RoleRef(rs.getString("code"), rs.getString("name")), rs.getBoolean("can_manage"), rs.getTimestamp("created_at").toInstant()), userId);
    }

    // —— 角色与权限 ——

    public RolesView roles() {
        Map<Long, Set<Permission>> permissions = currentPermissions();
        Map<Long, Long> counts = userCountsByRole();
        List<RoleItem> roles = allRoles().stream().map(r -> new RoleItem(r.code(), r.name(), r.description(), r.systemKind(),
                ordered(permissions.getOrDefault(r.id(), Set.of())), counts.getOrDefault(r.id(), 0L))).toList();
        List<CatalogItem> catalog = Arrays.stream(Permission.values()).map(p -> new CatalogItem(p.code(), p.group(), p.label(), p.detail(), p.lockAll(), p.lockAdmin(), p.guest())).toList();
        List<String> guest = Arrays.stream(Permission.values()).filter(Permission::guest).map(Permission::code).toList();
        return new RolesView(currentVersion(), MAX_ROLES, catalog, guest, roles, null);
    }

    @Transactional
    public RolesView updatePermissions(AccessContext operator, long version, Map<String, List<String>> requested, String note) {
        checkVersion(version);
        checkNote(note);
        if (requested == null) throw bad("请提供要保存的权限");
        Map<Long, Set<Permission>> current = currentPermissions();
        Map<Long, Set<Permission>> next = new HashMap<>(current);
        Map<String, RoleRow> byCode = allRoles().stream().collect(Collectors.toMap(RoleRow::code, r -> r));
        for (var entry : requested.entrySet()) {
            RoleRow role = byCode.get(entry.getKey());
            if (role == null) throw bad("角色不存在，可能已被删除，请刷新后重试");
            EnumSet<Permission> wanted = EnumSet.noneOf(Permission.class);
            for (String code : entry.getValue() == null ? List.<String>of() : entry.getValue())
                wanted.add(Permission.fromCode(code).orElseThrow(() -> bad("未知权限：" + code)));
            for (Permission p : Permission.values())
                if (p.lockedFor(role.systemKind()) && !wanted.contains(p))
                    throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "「" + role.name() + "」的「" + p.label() + "」不可关闭（防止锁死管理入口或影响基本登录能力）");
            next.put(role.id(), wanted);
        }
        if (managerCount(next, userCountsByRole()) < 1) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "保存后将没有任何用户能管理角色，请保留至少一位管理者");
        boolean changed = false;
        for (RoleRow role : allRoles()) {
            Set<Permission> before = current.getOrDefault(role.id(), Set.of()), after = next.getOrDefault(role.id(), Set.of());
            List<Permission> added = Arrays.stream(Permission.values()).filter(p -> after.contains(p) && !before.contains(p)).toList();
            List<Permission> removed = Arrays.stream(Permission.values()).filter(p -> before.contains(p) && !after.contains(p)).toList();
            if (added.isEmpty() && removed.isEmpty()) continue;
            changed = true;
            jdbc.update("delete from role_permissions where role_id=?", role.id());
            for (Permission p : after) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", role.id(), p.code());
            audit.permissions(operator.userId(), operator.displayName(), role, added, removed, note);
        }
        if (changed) bumpVersion();
        return roles();
    }

    @Transactional
    public RolesView createRole(AccessContext operator, long version, String name, String description, String copyFrom, String note) {
        checkVersion(version);
        checkNote(note);
        List<RoleRow> roles = allRoles();
        if (roles.size() >= MAX_ROLES) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "角色最多 " + MAX_ROLES + " 个，请先删除不用的角色");
        String cleanName = cleanName(name), cleanDescription = cleanDescription(description);
        requireUniqueName(cleanName, null);
        RoleRow source = null;
        if (copyFrom != null && !copyFrom.isBlank()) source = roleByCode(copyFrom).orElseThrow(() -> bad("要复制的角色不存在"));
        int sortOrder = roles.stream().filter(r -> !r.isAdmin()).mapToInt(RoleRow::sortOrder).max().orElse(0) + 10;
        var keys = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement s = c.prepareStatement("insert into roles(code,name,description,system_kind,sort_order,created_at,updated_at) values(?,?,?,null,?,?,?)", new String[]{"id"});
            s.setString(1, "NEW-" + UUID.randomUUID()); s.setString(2, cleanName); s.setString(3, cleanDescription); s.setInt(4, sortOrder);
            Timestamp now = new Timestamp(System.currentTimeMillis()); s.setTimestamp(5, now); s.setTimestamp(6, now);
            return s;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        String code = "R" + id;
        jdbc.update("update roles set code=? where id=?", code, id);
        Set<Permission> base = source == null ? Set.of() : currentPermissions().getOrDefault(source.id(), Set.of());
        for (Permission p : Permission.values())
            if (p.lockedFor(null) || base.contains(p)) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", id, p.code());
        RoleRow created = new RoleRow(id, code, cleanName, cleanDescription, null, sortOrder);
        audit.created(operator.userId(), operator.displayName(), created, source, note);
        bumpVersion();
        RolesView view = roles();
        return new RolesView(view.version(), view.maxRoles(), view.catalog(), view.guest(), view.roles(), code);
    }

    @Transactional
    public RolesView updateRole(AccessContext operator, String code, long version, String name, String description, String note) {
        checkVersion(version);
        checkNote(note);
        RoleRow role = roleByCode(code).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "角色不存在，可能已被删除"));
        String cleanName = cleanName(name), cleanDescription = cleanDescription(description);
        if (cleanName.equals(role.name()) && cleanDescription.equals(role.description())) return roles();
        requireUniqueName(cleanName, role.id());
        jdbc.update("update roles set name=?,description=?,updated_at=current_timestamp where id=?", cleanName, cleanDescription, role.id());
        audit.updated(operator.userId(), operator.displayName(), role, cleanName, note);
        bumpVersion();
        return roles();
    }

    @Transactional
    public RolesView deleteRole(AccessContext operator, String code, long version, String moveTo, String note) {
        checkVersion(version);
        checkNote(note);
        RoleRow role = roleByCode(code).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "角色不存在，可能已被删除"));
        if (role.isAdmin()) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "管理员角色不能删除");
        if (role.isDefault()) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "注册默认角色不能删除，可以改名或调整权限");
        List<String> memberNames = jdbc.queryForList("select display_name from user_accounts where role_id=? order by id", String.class, role.id());
        RoleRow target = null;
        if (!memberNames.isEmpty()) {
            if (moveTo == null || moveTo.isBlank() || moveTo.equals(role.code()))
                throw bad("该角色下还有 " + memberNames.size() + " 位用户，请选择他们改用的角色");
            target = roleByCode(moveTo).orElseThrow(() -> bad("转入的角色不存在，请刷新后重试"));
            Map<Long, Long> counts = userCountsByRole();
            counts.merge(target.id(), (long) memberNames.size(), Long::sum);
            counts.remove(role.id());
            Map<Long, Set<Permission>> permissions = currentPermissions();
            permissions.remove(role.id());
            if (managerCount(permissions, counts) < 1) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "删除后将没有任何用户能管理角色");
            jdbc.update("update user_accounts set role_id=? where role_id=?", target.id(), role.id());
        }
        jdbc.update("delete from role_permissions where role_id=?", role.id());
        jdbc.update("delete from roles where id=?", role.id());
        audit.deleted(operator.userId(), operator.displayName(), role, target, memberNames.subList(0, Math.min(10, memberNames.size())), memberNames.size(), note);
        bumpVersion();
        return roles();
    }

    // —— 操作记录 ——

    public AuditPage audit(String q, String type, String role, String from, String to, int page, int size) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (query.length() > 80) throw bad("搜索内容最多 80 个字");
        if (type != null && !type.isBlank() && !List.of("ROLE_ASSIGN", "ROLE_PERMISSION", "ROLE_DEFINE").contains(type)) throw bad("记录类型不正确");
        Timestamp start = parseDay(from, false), end = parseDay(to, true);
        if (start != null && end != null && start.after(end)) throw bad("开始日期不能晚于结束日期");
        int safePage = Math.max(0, Math.min(page, 10_000)), safeSize = Math.max(1, Math.min(size, 50));
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) { where.append(" and type=?"); args.add(type); }
        if (role != null && !role.isBlank()) { where.append(" and ? in (role_code,before_role_code,after_role_code,copy_from_code,move_to_code)"); args.add(role.trim()); }
        if (start != null) { where.append(" and created_at>=?"); args.add(start); }
        if (end != null) { where.append(" and created_at<?"); args.add(end); }
        if (!query.isEmpty()) {
            List<String> permissionCodes = Arrays.stream(Permission.values()).filter(p -> p.label().toLowerCase(Locale.ROOT).contains(query)).map(Permission::code).toList();
            String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" and (lower(coalesce(operator_name,'')) like ? escape '\\' or lower(coalesce(target_user_name,'')) like ? escape '\\' or lower(coalesce(role_name,'')) like ? escape '\\'")
                    .append(" or lower(coalesce(before_role_name,'')) like ? escape '\\' or lower(coalesce(after_role_name,'')) like ? escape '\\' or lower(coalesce(copy_from_name,'')) like ? escape '\\'")
                    .append(" or lower(coalesce(move_to_name,'')) like ? escape '\\' or lower(coalesce(moved_names,'')) like ? escape '\\' or lower(note) like ? escape '\\'");
            for (int i = 0; i < 9; i++) args.add(like);
            for (String code : permissionCodes) {
                where.append(" or coalesce(added_permissions,'') like ? or coalesce(removed_permissions,'') like ?");
                args.add("%" + code + "%"); args.add("%" + code + "%");
            }
            where.append(")");
        }
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(safeSize); pageArgs.add((long) safePage * safeSize);
        List<AuditItem> items = jdbc.query("select * from access_audit_log" + where + " order by created_at desc,id desc limit ? offset ?", (rs, n) -> new AuditItem(
                rs.getLong("id"), rs.getString("type"), rs.getString("action"), rs.getTimestamp("created_at").toInstant(), rs.getString("operator_name"),
                rs.getString("target_user_name"), rs.getString("role_name"), rs.getString("before_role_name"), rs.getString("after_role_name"),
                rs.getString("copy_from_name"), rs.getString("move_to_name"), labels(rs.getString("added_permissions")), labels(rs.getString("removed_permissions")),
                (Integer) rs.getObject("moved_count"), rs.getString("moved_names") == null || rs.getString("moved_names").isEmpty() ? List.of() : List.of(rs.getString("moved_names").split("、")),
                rs.getString("note")), pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) from access_audit_log" + where, Long.class, args.toArray());
        return new AuditPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    // —— 内部 ——

    private void lockVersion() { jdbc.queryForObject("select version from access_control_state where id=1 for update", Long.class); }
    private long currentVersion() { Long v = jdbc.queryForObject("select version from access_control_state where id=1", Long.class); return v == null ? 0 : v; }
    private void checkVersion(long version) {
        Long current = jdbc.queryForObject("select version from access_control_state where id=1 for update", Long.class);
        if (current == null || current != version) throw new ApiProblem(HttpStatus.CONFLICT, "角色设置已被其他管理员修改，请刷新后重试");
    }
    private void bumpVersion() { jdbc.update("update access_control_state set version=version+1 where id=1"); }

    private List<RoleRow> allRoles() { return jdbc.query("select " + RoleRow.COLUMNS + " from roles order by sort_order,id", RoleRow::map); }
    private Optional<RoleRow> roleByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return jdbc.query("select " + RoleRow.COLUMNS + " from roles where code=?", RoleRow::map, code.trim()).stream().findFirst();
    }

    private Map<Long, Set<Permission>> currentPermissions() {
        Map<Long, Set<Permission>> result = new HashMap<>();
        jdbc.query("select role_id,permission from role_permissions", rs -> {
            long roleId = rs.getLong("role_id");
            Permission.fromCode(rs.getString("permission")).ifPresent(p -> result.computeIfAbsent(roleId, k -> EnumSet.noneOf(Permission.class)).add(p));
        });
        return result;
    }
    private Map<Long, Long> userCountsByRole() {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.query("select coalesce(role_id,(select d.id from roles d where d.system_kind='DEFAULT')) as role_id,count(*) as n from user_accounts group by coalesce(role_id,(select d.id from roles d where d.system_kind='DEFAULT'))",
                rs -> { counts.merge(rs.getLong("role_id"), rs.getLong("n"), Long::sum); });
        return counts;
    }
    private static long managerCount(Map<Long, Set<Permission>> permissions, Map<Long, Long> counts) {
        return counts.entrySet().stream().filter(e -> permissions.getOrDefault(e.getKey(), Set.of()).contains(Permission.ADMIN_ROLES)).mapToLong(Map.Entry::getValue).sum();
    }
    private static List<String> ordered(Set<Permission> permissions) {
        return Arrays.stream(Permission.values()).filter(permissions::contains).map(Permission::code).toList();
    }
    private static List<String> labels(String codes) {
        if (codes == null || codes.isBlank()) return List.of();
        return Arrays.stream(codes.split(",")).map(c -> Permission.fromCode(c).map(Permission::label).orElse(c)).toList();
    }

    private void requireUniqueName(String name, Long exceptId) {
        Integer clash = exceptId == null
                ? jdbc.queryForObject("select count(*) from roles where lower(name)=lower(?)", Integer.class, name)
                : jdbc.queryForObject("select count(*) from roles where lower(name)=lower(?) and id<>?", Integer.class, name, exceptId);
        if (clash != null && clash > 0) throw new ApiProblem(HttpStatus.CONFLICT, "已有名为「" + name + "」的角色");
    }
    private static String cleanName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) throw bad("请填写角色名称");
        if (value.codePointCount(0, value.length()) > 20) throw bad("角色名称最多 20 个字");
        return value;
    }
    private static String cleanDescription(String description) {
        String value = description == null ? "" : description.trim();
        if (value.codePointCount(0, value.length()) > 60) throw bad("角色说明最多 60 个字");
        return value;
    }
    private static void checkNote(String note) { if (note != null && note.trim().length() > 200) throw bad("备注最多 200 个字"); }
    private Timestamp parseDay(String day, boolean exclusiveEnd) {
        if (day == null || day.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(day.trim());
            return Timestamp.from((exclusiveEnd ? date.plusDays(1) : date).atStartOfDay(auditZone).toInstant());
        } catch (DateTimeParseException e) { throw bad("日期格式应为 YYYY-MM-DD"); }
    }
    private static Object[] concat(Object[] args, Object... more) {
        Object[] result = Arrays.copyOf(args, args.length + more.length);
        System.arraycopy(more, 0, result, args.length, more.length);
        return result;
    }
    private static ApiProblem bad(String message) { return new ApiProblem(HttpStatus.BAD_REQUEST, message); }

    public record RoleRef(String code, String name) {}
    public record UserItem(long id, String displayName, String email, RoleRef role, boolean canManage, java.time.Instant createdAt) {}
    public record UserPage(List<UserItem> items, int page, int size, long total, long managerCount) {}
    public record CatalogItem(String code, String group, String label, String detail, boolean lockAll, boolean lockAdmin, boolean guest) {}
    public record RoleItem(String code, String name, String description, String systemKind, List<String> permissions, long userCount) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RolesView(long version, int maxRoles, List<CatalogItem> catalog, List<String> guest, List<RoleItem> roles, String createdCode) {}
    public record AuditItem(long id, String type, String action, java.time.Instant createdAt, String operatorName, String targetUserName,
                            String roleName, String beforeRoleName, String afterRoleName, String copyFromName, String moveToName,
                            List<String> addedPermissions, List<String> removedPermissions, Integer movedCount, List<String> movedNames, String note) {}
    public record AuditPage(List<AuditItem> items, int page, int size, long total) {}
}
