package com.aitome.access;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户角色分配、角色权限修改、角色新增/改名/删除、账号停用/恢复、站长转交与操作记录查询（契约 §5；FINNS-16 契约 §3–§6）。
 * 所有写操作在一个事务内先锁定 access_control_state 行，再校验操作者层级、比对版本并保证至少保留一位正常的管理者。
 */
@Service
public class AccessAdminService {
    public static final int MAX_ROLES = 12;
    // 角色为空的用户（例如回退到 0.6.0 期间注册的用户）按注册默认角色处理，下次启动时由 AccessBootstrap 补齐。
    private static final Logger log = LoggerFactory.getLogger(AccessAdminService.class);
    public static final String MSG_OWNER_TARGET = "站长不能停用或直接调整角色，请由当前站长使用专门的转交入口。";
    public static final String MSG_OTHER_ADMIN = "管理员不能管理其他管理员，请由站长处理。";
    public static final String MSG_SELF_ROLE = "不能调整自己的角色，请由站长处理。";
    public static final String MSG_OWNER_UNIQUE = "站长为唯一身份，请使用专门的转交入口。";
    public static final String MSG_GRANT = "只有站长可以授予管理员身份或管理角色权限。";
    public static final String MSG_SELF_DISABLE = "不能停用自己的账号，请由另一位有权限的管理者操作。";
    public static final String MSG_STATUS_CONFLICT = "该账号状态已被其他管理者更新，请核对最新状态后再次确认。";
    public static final String MSG_NO_MANAGER = "操作后将没有正常管理者，请先安排另一位正常且能管理角色的用户。";
    public static final String MSG_STATUS_UNAVAILABLE = "暂时无法保存，账号状态未改变。原因已保留，请重试。";
    public static final String MSG_TRANSFER_UNAVAILABLE = "转交未完成，双方身份均未改变。填写内容已保留，请重试。";

    private final JdbcTemplate jdbc;
    private final AccessAuditWriter audit;
    private final ZoneId auditZone;
    private final AccessService access;
    private final TransactionTemplate tx;

    public AccessAdminService(JdbcTemplate jdbc, AccessAuditWriter audit, AccessService access, TransactionTemplate tx,
                              @Value("${finns.access.audit-zone:Asia/Shanghai}") String auditZone) {
        this.jdbc = jdbc; this.audit = audit; this.access = access; this.tx = tx; this.auditZone = ZoneId.of(auditZone);
    }

    // —— 用户 ——

    public UserPage users(AccessContext viewer, String q, String roleCode, String status, int page, int size) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (query.length() > 80) throw bad("搜索内容最多 80 个字");
        String statusFilter = status == null ? "" : status.trim();
        if (!statusFilter.isEmpty() && !List.of("ACTIVE", "DISABLED").contains(statusFilter)) throw bad("账号状态不正确");
        int safePage = Math.max(0, Math.min(page, 10_000)), safeSize = Math.max(1, Math.min(size, 50));
        String like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String role = roleCode == null ? "" : roleCode.trim();
        String where = " where (?='' or lower(u.display_name) like ? escape '\\' or lower(u.email) like ? escape '\\') and (?='' or r.code=?) and (?='' or u.account_status=?)";
        Object[] args = {query, like, like, role, role, statusFilter, statusFilter};
        Operator operator = viewerOf(viewer);
        List<UserItem> items = jdbc.query(USER_SELECT + where + " order by u.created_at asc,u.id asc limit ? offset ?",
                (rs, n) -> item(operator, USER_ROW.mapRow(rs, n)), concat(args, safeSize, (long) safePage * safeSize));
        Long total = jdbc.queryForObject("select count(*) from user_accounts u join roles r on r.id=coalesce(u.role_id,(select d.id from roles d where d.system_kind='DEFAULT'))" + where, Long.class, args);
        return new UserPage(items, safePage, safeSize, total == null ? 0 : total, managerCount(currentPermissions(), activeCountsByRole()));
    }

    @Transactional
    public UserItem assignRole(AccessContext operatorContext, long userId, String roleCode, String expectedRoleCode, String note) {
        State state = lockState();
        Operator operator = operatorOf(operatorContext, state, Permission.ADMIN_USERS);
        checkNote(note);
        UserRow user = findUser(userId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "用户不存在"));
        RoleRow target = roleByCode(roleCode).orElseThrow(() -> bad("目标角色不存在，可能已被删除，请刷新后重试"));
        String restriction = restriction(operator, user, target, isProtectedRole(target, permissionsOf(target)), true);
        if (!restriction.isEmpty()) throw new ApiProblem(HttpStatus.FORBIDDEN, restriction);
        String currentCode = user.roleCode();
        if (expectedRoleCode != null && !expectedRoleCode.isBlank() && !expectedRoleCode.equals(currentCode))
            throw new ApiProblem(HttpStatus.CONFLICT, "该用户角色已被修改为「" + user.roleName() + "」，请核对后重试");
        if (!target.code().equals(currentCode)) {
            Map<Long, Long> counts = activeCountsByRole();
            if (user.active()) {
                counts.merge(user.roleId(), -1L, Long::sum);
                counts.merge(target.id(), 1L, Long::sum);
            }
            if (managerCount(currentPermissions(), counts) < 1)
                throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "这是最后一位能管理角色的用户，不能改为没有管理权限的角色；请先指定另一位管理员");
            jdbc.update("update user_accounts set role_id=? where id=?", target.id(), userId);
            bumpVersion();
            audit.assign(operator.id(), operator.name(), userId, user.displayName(), currentCode, user.roleName(), target.code(), target.name(), note);
        }
        return item(operator, findUser(userId).orElseThrow());
    }

    // —— 账号停用与恢复（FINNS-16） ——

    /** 停用/恢复。成功记录与变更同事务；校验拒绝与保存失败的记录在回滚后独立写入。 */
    public UserItem changeStatus(AccessContext operatorContext, long userId, String status, Long expectedVersion, String note) {
        UserRow before = findUser(userId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) throw bad("请选择有效账号状态。");
        String action = "DISABLED".equals(status) ? "DISABLE" : "RESTORE";
        boolean[] authorized = {false};
        try {
            return tx.execute(txStatus -> doChangeStatus(operatorContext, userId, status, action, expectedVersion, note, authorized));
        } catch (ApiProblem problem) {
            if (authorized[0] && problem.status() != 401 && problem.status() != 404) recordStatusFailure(operatorContext, userId, before, action, note, problem.status() >= 500 ? "FAILED" : "REJECTED", problem.getMessage());
            throw problem;
        } catch (DataAccessException e) {
            log.warn("账号状态保存失败 user={} action={}", userId, action, e);
            recordStatusFailure(operatorContext, userId, before, action, note, "FAILED", MSG_STATUS_UNAVAILABLE);
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, MSG_STATUS_UNAVAILABLE);
        }
    }

    private UserItem doChangeStatus(AccessContext operatorContext, long userId, String status, String action, Long expectedVersion, String note, boolean[] authorized) {
        State state = lockState();
        Operator operator = operatorOf(operatorContext, state, Permission.ADMIN_USERS);
        authorized[0] = true;
        UserRow user = findUser(userId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "用户不存在"));
        String reason = note == null ? "" : note.trim();
        int length = reason.codePointCount(0, reason.length());
        if (length < 1 || length > 200) throw bad("请填写1至200字的操作原因。");
        if (userId == operator.id() && "DISABLED".equals(status)) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, MSG_SELF_DISABLE);
        String restriction = restriction(operator, user, null, false, false);
        if (!restriction.isEmpty()) throw new ApiProblem(HttpStatus.FORBIDDEN, restriction);
        if (user.accountStatus().equals(status)) return item(operator, user); // 幂等：目标状态已达成，不重复生效、不重复记录
        if (expectedVersion == null || expectedVersion != user.statusVersion()) throw new ApiProblem(HttpStatus.CONFLICT, MSG_STATUS_CONFLICT);
        if ("DISABLED".equals(status) && user.canManage()) {
            Map<Long, Long> counts = activeCountsByRole();
            counts.merge(user.roleId(), -1L, Long::sum);
            if (managerCount(currentPermissions(), counts) < 1) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, MSG_NO_MANAGER);
        }
        int changed = jdbc.update("update user_accounts set account_status=?, status_version=status_version+1, session_epoch=session_epoch+1 where id=? and status_version=?",
                status, userId, user.statusVersion());
        if (changed != 1) throw new ApiProblem(HttpStatus.CONFLICT, MSG_STATUS_CONFLICT);
        audit.status(operator.id(), operator.name(), userId, user.displayName(), user.roleCode(), user.roleName(), action, user.accountStatus(), status, reason, "SUCCESS",
                "DISABLED".equals(status) ? "已停用；旧会话失效，作品保持原可见范围" : "已恢复；须重新登录，旧会话不恢复");
        return item(operator, findUser(userId).orElseThrow());
    }

    private void recordStatusFailure(AccessContext operatorContext, long userId, UserRow before, String action, String note, String outcome, String message) {
        try {
            UserRow current = findUser(userId).orElse(before);
            audit.status(operatorContext.userId(), operatorContext.displayName(), userId, current.displayName(), current.roleCode(), current.roleName(), action,
                    current.accountStatus(), current.accountStatus(), note, outcome, message);
        } catch (RuntimeException e) {
            log.warn("无法写入账号状态的{}记录 user={}", outcome, userId, e);
        }
    }

    // —— 站长转交（FINNS-16） ——

    public OwnershipView ownership(AccessContext operatorContext) {
        State state = readState();
        Operator operator = viewerOf(operatorContext);
        if (!operator.owner() || !Objects.equals(state.ownerUserId(), operator.id())) throw new ApiProblem(HttpStatus.FORBIDDEN, "只有当前站长可以转交站长身份。");
        OwnerRef owner = ownerRef(operator.id());
        List<OwnerRef> candidates = jdbc.query("select u.id,u.display_name,u.email from user_accounts u join roles r on r.id=u.role_id where r.system_kind='ADMIN' and u.account_status='ACTIVE' and u.id<>? order by u.created_at,u.id",
                (rs, n) -> new OwnerRef(rs.getLong(1), rs.getString(2), rs.getString(3)), operator.id());
        return new OwnershipView(owner, state.ownerVersion(), candidates);
    }

    public TransferOutcome transferOwner(AccessContext operatorContext, Long targetId, Long expectedOwnerId, Long expectedVersion, String note, String confirmEmail) {
        boolean[] authorized = {false};
        try {
            return tx.execute(txStatus -> doTransfer(operatorContext, targetId, expectedOwnerId, expectedVersion, note, confirmEmail, authorized));
        } catch (ApiProblem problem) {
            if (authorized[0] && problem.status() != 401) recordTransferFailure(operatorContext, targetId, note, problem.status() >= 500 ? "FAILED" : "REJECTED", problem.getMessage());
            throw problem;
        } catch (DataAccessException e) {
            log.warn("站长转交保存失败 target={}", targetId, e);
            recordTransferFailure(operatorContext, targetId, note, "FAILED", MSG_TRANSFER_UNAVAILABLE);
            throw new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, MSG_TRANSFER_UNAVAILABLE);
        }
    }

    private TransferOutcome doTransfer(AccessContext operatorContext, Long targetId, Long expectedOwnerId, Long expectedVersion, String note, String confirmEmail, boolean[] authorized) {
        State state = lockState();
        Operator operator = operatorOf(operatorContext, state, Permission.ADMIN_USERS);
        authorized[0] = true;
        if (!operator.owner()) throw new ApiProblem(HttpStatus.FORBIDDEN, "只有当前站长可以转交站长身份。");
        if (!Objects.equals(expectedOwnerId, operator.id()) || expectedVersion == null || expectedVersion != state.ownerVersion())
            throw new ApiProblem(HttpStatus.CONFLICT, "站长信息已变化，请重新核对接任人后确认。");
        UserRow target = targetId == null ? null : findUser(targetId).orElse(null);
        if (target == null || target.id() == operator.id() || !Role.KIND_ADMIN.equals(target.systemKind()) || !target.active())
            throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "接任人必须是另一位正常管理员，请重新选择。");
        String reason = note == null ? "" : note.trim();
        int length = reason.codePointCount(0, reason.length());
        if (length < 1 || length > 200) throw bad("请填写1至200字的转交原因。");
        if (confirmEmail == null || !confirmEmail.trim().equalsIgnoreCase(target.email())) throw bad("请输入接任人的完整邮箱以确认转交。");
        RoleRow adminRole = roleByKind(Role.KIND_ADMIN), ownerRole = roleByKind(Role.KIND_OWNER);
        jdbc.update("update user_accounts set role_id=?, session_epoch=session_epoch+1 where id=?", adminRole.id(), operator.id());
        jdbc.update("update user_accounts set role_id=?, session_epoch=session_epoch+1 where id=?", ownerRole.id(), target.id());
        jdbc.update("update access_control_state set owner_user_id=?, owner_version=owner_version+1, version=version+1 where id=1", target.id());
        audit.owner(operator.id(), operator.name(), target.id(), target.displayName(), adminRole.code(), adminRole.name(), ownerRole.code(), ownerRole.name(), "TRANSFER", reason, "SUCCESS",
                "唯一站长已转交；原站长成为管理员，双方其他旧会话失效，接任人需重新登录。");
        Long epoch = jdbc.queryForObject("select session_epoch from user_accounts where id=?", Long.class, operator.id()); // 行已被本事务更新，读到的是新值
        return new TransferOutcome(ownerRef(target.id()), new RoleRef(adminRole.code(), adminRole.name()), epoch == null ? 0 : epoch);
    }

    private void recordTransferFailure(AccessContext operatorContext, Long targetId, String note, String outcome, String message) {
        try {
            UserRow target = targetId == null ? null : findUser(targetId).orElse(null);
            audit.owner(operatorContext.userId(), operatorContext.displayName(), target == null ? null : target.id(), target == null ? "无效接任人" : target.displayName(),
                    target == null ? null : target.roleCode(), target == null ? null : target.roleName(), null, null, "TRANSFER", note, outcome, message);
        } catch (RuntimeException e) {
            log.warn("无法写入站长转交的{}记录", outcome, e);
        }
    }

    // —— 角色与权限 ——

    public RolesView roles(AccessContext viewer) { return rolesView(viewerOf(viewer), null); }

    private RolesView rolesView(Operator operator, String createdCode) {
        Map<Long, Set<Permission>> permissions = currentPermissions();
        Map<Long, Long> counts = userCountsByRole();
        List<RoleItem> roles = allRoles().stream().map(r -> {
            boolean manageable = !r.isOwner() && (operator.owner() || !isProtectedRole(r, permissions.getOrDefault(r.id(), Set.of())));
            return new RoleItem(r.code(), r.name(), r.description(), r.systemKind(), ordered(permissions.getOrDefault(r.id(), Set.of())), counts.getOrDefault(r.id(), 0L), manageable, manageable);
        }).toList();
        List<CatalogItem> catalog = Arrays.stream(Permission.values()).map(p -> new CatalogItem(p.code(), p.group(), p.label(), p.detail(), p.lockAll(), p.lockAdmin(), p.guest())).toList();
        List<String> guest = Arrays.stream(Permission.values()).filter(Permission::guest).map(Permission::code).toList();
        return new RolesView(currentVersion(), MAX_ROLES, catalog, guest, roles, createdCode);
    }

    @Transactional
    public RolesView updatePermissions(AccessContext operatorContext, long version, Map<String, List<String>> requested, String note) {
        State state = lockState();
        if (state.version() != version) throw new ApiProblem(HttpStatus.CONFLICT, "角色设置已被其他管理员修改，请刷新后重试");
        Operator operator = operatorOf(operatorContext, state, Permission.ADMIN_ROLES);
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
        if (!operator.owner()) {
            for (RoleRow role : allRoles()) {
                Set<Permission> before = current.getOrDefault(role.id(), Set.of()), after = next.getOrDefault(role.id(), Set.of());
                if (!before.equals(after) && isProtectedRole(role, before)) throw new ApiProblem(HttpStatus.FORBIDDEN, "只有站长可以调整管理员角色的权限。");
                if (after.stream().anyMatch(p -> p.code().startsWith("admin.") && !before.contains(p))) throw new ApiProblem(HttpStatus.FORBIDDEN, "只有站长可以授予管理权限。");
            }
        }
        if (managerCount(next, activeCountsByRole()) < 1) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "保存后将没有任何用户能管理角色，请保留至少一位管理者");
        boolean changed = false;
        for (RoleRow role : allRoles()) {
            Set<Permission> before = current.getOrDefault(role.id(), Set.of()), after = next.getOrDefault(role.id(), Set.of());
            List<Permission> added = Arrays.stream(Permission.values()).filter(p -> after.contains(p) && !before.contains(p)).toList();
            List<Permission> removed = Arrays.stream(Permission.values()).filter(p -> before.contains(p) && !after.contains(p)).toList();
            if (added.isEmpty() && removed.isEmpty()) continue;
            changed = true;
            jdbc.update("delete from role_permissions where role_id=?", role.id());
            for (Permission p : after) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", role.id(), p.code());
            audit.permissions(operator.id(), operator.name(), role, added, removed, note);
        }
        if (changed) bumpVersion();
        return rolesView(operator, null);
    }

    @Transactional
    public RolesView createRole(AccessContext operatorContext, long version, String name, String description, String copyFrom, String note) {
        State state = lockState();
        if (state.version() != version) throw new ApiProblem(HttpStatus.CONFLICT, "角色设置已被其他管理员修改，请刷新后重试");
        Operator operator = operatorOf(operatorContext, state, Permission.ADMIN_ROLES);
        checkNote(note);
        List<RoleRow> roles = allRoles();
        if (roles.size() >= MAX_ROLES) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "角色最多 " + MAX_ROLES + " 个，请先删除不用的角色");
        String cleanName = cleanName(name), cleanDescription = cleanDescription(description);
        requireUniqueName(cleanName, null);
        RoleRow source = null;
        if (copyFrom != null && !copyFrom.isBlank()) source = roleByCode(copyFrom).orElseThrow(() -> bad("要复制的角色不存在"));
        Map<Long, Set<Permission>> permissions = currentPermissions();
        if (source != null && source.isOwner()) throw new ApiProblem(HttpStatus.FORBIDDEN, "站长角色不能复制。");
        if (source != null && !operator.owner() && isProtectedRole(source, permissions.getOrDefault(source.id(), Set.of())))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "只有站长可以创建具有管理权限的角色。");
        int sortOrder = roles.stream().filter(r -> !r.isAdmin() && !r.isOwner()).mapToInt(RoleRow::sortOrder).max().orElse(0) + 10;
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
        Set<Permission> base = source == null ? Set.of() : permissions.getOrDefault(source.id(), Set.of());
        for (Permission p : Permission.values())
            if (p.lockedFor(null) || base.contains(p)) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", id, p.code());
        RoleRow created = new RoleRow(id, code, cleanName, cleanDescription, null, sortOrder);
        audit.created(operator.id(), operator.name(), created, source, note);
        bumpVersion();
        RolesView view = rolesView(operator, null);
        return new RolesView(view.version(), view.maxRoles(), view.catalog(), view.guest(), view.roles(), code);
    }

    @Transactional
    public RolesView updateRole(AccessContext operatorContext, String code, long version, String name, String description, String note) {
        State state = lockState();
        if (state.version() != version) throw new ApiProblem(HttpStatus.CONFLICT, "角色设置已被其他管理员修改，请刷新后重试");
        Operator operator = operatorOf(operatorContext, state, Permission.ADMIN_ROLES);
        checkNote(note);
        RoleRow role = roleByCode(code).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "角色不存在，可能已被删除"));
        if (role.isOwner()) throw new ApiProblem(HttpStatus.FORBIDDEN, "站长身份及说明固定，不能在这里修改。");
        if (!operator.owner() && isProtectedRole(role, permissionsOf(role))) throw new ApiProblem(HttpStatus.FORBIDDEN, "只有站长可以编辑管理员角色。");
        String cleanName = cleanName(name), cleanDescription = cleanDescription(description);
        if (cleanName.equals(role.name()) && cleanDescription.equals(role.description())) return rolesView(operator, null);
        requireUniqueName(cleanName, role.id());
        jdbc.update("update roles set name=?,description=?,updated_at=current_timestamp where id=?", cleanName, cleanDescription, role.id());
        audit.updated(operator.id(), operator.name(), role, cleanName, note);
        bumpVersion();
        return rolesView(operator, null);
    }

    @Transactional
    public RolesView deleteRole(AccessContext operatorContext, String code, long version, String moveTo, String note) {
        State state = lockState();
        if (state.version() != version) throw new ApiProblem(HttpStatus.CONFLICT, "角色设置已被其他管理员修改，请刷新后重试");
        Operator operator = operatorOf(operatorContext, state, Permission.ADMIN_ROLES);
        checkNote(note);
        RoleRow role = roleByCode(code).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "角色不存在，可能已被删除"));
        if (role.isOwner()) throw new ApiProblem(HttpStatus.FORBIDDEN, "站长角色不能删除。");
        if (!operator.owner() && isProtectedRole(role, permissionsOf(role))) throw new ApiProblem(HttpStatus.FORBIDDEN, "只有站长可以处置具有管理权限的角色。");
        if (role.isAdmin()) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "管理员角色不能删除");
        if (role.isDefault()) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "注册默认角色不能删除，可以改名或调整权限");
        List<String> memberNames = jdbc.queryForList("select display_name from user_accounts where role_id=? order by id", String.class, role.id());
        RoleRow target = null;
        if (!memberNames.isEmpty()) {
            if (moveTo == null || moveTo.isBlank() || moveTo.equals(role.code()))
                throw bad("该角色下还有 " + memberNames.size() + " 位用户，请选择他们改用的角色");
            target = roleByCode(moveTo).orElseThrow(() -> bad("转入的角色不存在，请刷新后重试"));
            Map<Long, Set<Permission>> permissions = currentPermissions();
            if (target.isOwner()) throw new ApiProblem(HttpStatus.FORBIDDEN, "站长身份不能通过删除角色或迁移用户改变。");
            if (!operator.owner() && isProtectedRole(target, permissions.getOrDefault(target.id(), Set.of()))) throw new ApiProblem(HttpStatus.FORBIDDEN, "只有站长可以授予管理员身份。");
            Map<Long, Long> counts = activeCountsByRole();
            long movedActive = jdbc.queryForObject("select count(*) from user_accounts where role_id=? and account_status='ACTIVE'", Long.class, role.id());
            counts.merge(target.id(), movedActive, Long::sum);
            counts.remove(role.id());
            permissions.remove(role.id());
            if (managerCount(permissions, counts) < 1) throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "删除后将没有任何用户能管理角色");
            jdbc.update("update user_accounts set role_id=? where role_id=?", target.id(), role.id());
        }
        jdbc.update("delete from role_permissions where role_id=?", role.id());
        jdbc.update("delete from roles where id=?", role.id());
        audit.deleted(operator.id(), operator.name(), role, target, memberNames.subList(0, Math.min(10, memberNames.size())), memberNames.size(), note);
        bumpVersion();
        return rolesView(operator, null);
    }

    // —— 操作记录 ——

    public AuditPage audit(String q, String type, String role, String from, String to, int page, int size) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (query.length() > 80) throw bad("搜索内容最多 80 个字");
        if (type != null && !type.isBlank() && !List.of("ROLE_ASSIGN", "ROLE_PERMISSION", "ROLE_DEFINE", "ACCOUNT_STATUS", "OWNER_TRANSFER").contains(type)) throw bad("记录类型不正确");
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
                    .append(" or lower(coalesce(move_to_name,'')) like ? escape '\\' or lower(coalesce(moved_names,'')) like ? escape '\\' or lower(note) like ? escape '\\' or lower(coalesce(result_message,'')) like ? escape '\\'");
            for (int i = 0; i < 10; i++) args.add(like);
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
                rs.getString("note"), rs.getString("before_status"), rs.getString("after_status"), rs.getString("outcome"), rs.getString("result_message")), pageArgs.toArray());
        Long total = jdbc.queryForObject("select count(*) from access_audit_log" + where, Long.class, args.toArray());
        return new AuditPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    // —— 内部 ——

    record State(long version, Long ownerUserId, long ownerVersion) {}
    /** 锁内重读的操作者：owner＝系统种类为 OWNER 且等于站长指针。 */
    record Operator(long id, String name, boolean owner) {}
    record UserRow(long id, String displayName, String email, java.time.Instant createdAt, String accountStatus, long statusVersion,
                   long roleId, String roleCode, String roleName, String systemKind, boolean canManage) {
        boolean active() { return !"DISABLED".equals(accountStatus); }
        boolean owner() { return Role.KIND_OWNER.equals(systemKind); }
        boolean protectedRole() { return owner() || Role.KIND_ADMIN.equals(systemKind) || canManage; }
    }

    private static final String USER_SELECT = "select u.id,u.display_name,u.email,u.created_at,u.account_status,u.status_version,r.id as role_id,r.code,r.name,r.system_kind," +
            " exists(select 1 from role_permissions rp where rp.role_id=r.id and rp.permission='admin.roles') as can_manage" +
            " from user_accounts u join roles r on r.id=coalesce(u.role_id,(select d.id from roles d where d.system_kind='DEFAULT'))";
    private static final org.springframework.jdbc.core.RowMapper<UserRow> USER_ROW = (rs, n) -> new UserRow(rs.getLong("id"), rs.getString("display_name"), rs.getString("email"),
            rs.getTimestamp("created_at").toInstant(), rs.getString("account_status"), rs.getLong("status_version"), rs.getLong("role_id"), rs.getString("code"),
            rs.getString("name"), rs.getString("system_kind"), rs.getBoolean("can_manage"));

    private Optional<UserRow> findUser(long id) { return jdbc.query(USER_SELECT + " where u.id=?", USER_ROW, id).stream().findFirst(); }

    private State lockState() {
        return jdbc.queryForObject("select version,owner_user_id,owner_version from access_control_state where id=1 for update",
                (rs, n) -> new State(rs.getLong(1), (Long) rs.getObject(2), rs.getLong(3)));
    }
    private State readState() {
        return jdbc.queryForObject("select version,owner_user_id,owner_version from access_control_state where id=1",
                (rs, n) -> new State(rs.getLong(1), (Long) rs.getObject(2), rs.getLong(3)));
    }

    /** 锁内重读操作者，不使用请求入口的快照（站长转交与其他管理操作可能并发）。 */
    private Operator operatorOf(AccessContext context, State state, Permission required) {
        if (context == null || context.isGuest()) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效");
        AccessContext fresh = access.forUser(context.userId());
        if (fresh == null || !fresh.active() || fresh.sessionEpoch() != context.sessionEpoch()) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效");
        if (!fresh.has(required)) throw new ApiProblem(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
        return new Operator(fresh.userId(), fresh.displayName(), fresh.isOwner() && Objects.equals(state.ownerUserId(), fresh.userId()));
    }
    /** 只读视图使用（列表/角色/转交候选），不加锁；写接口仍独立校验。 */
    private Operator viewerOf(AccessContext context) {
        if (context == null || context.isGuest()) return new Operator(-1, "", false);
        return new Operator(context.userId(), context.displayName(), context.isOwner() && Objects.equals(readState().ownerUserId(), context.userId()));
    }

    /** 层级规则（契约 §3）。返回 403 文案，空串表示允许。nextRole 为空表示不涉及目标角色（停用/恢复）。 */
    private static String restriction(Operator operator, UserRow target, RoleRow nextRole, boolean nextProtected, boolean roleAssign) {
        if (target.owner()) return MSG_OWNER_TARGET;
        if (target.protectedRole() && !operator.owner()) return target.id() == operator.id() && roleAssign ? MSG_SELF_ROLE : MSG_OTHER_ADMIN;
        if (nextRole != null && nextRole.isOwner()) return MSG_OWNER_UNIQUE;
        if (nextRole != null && nextProtected && !operator.owner()) return MSG_GRANT;
        return "";
    }

    private UserItem item(Operator operator, UserRow row) {
        boolean self = row.id() == operator.id();
        String roleBlock = restriction(operator, row, null, false, true);
        String statusBlock = self ? "不能停用自己的账号。" : restriction(operator, row, null, false, false);
        return new UserItem(row.id(), row.displayName(), row.email(), new RoleRef(row.roleCode(), row.roleName()), row.canManage(), row.createdAt(),
                row.accountStatus(), row.statusVersion(), row.owner(), roleBlock.isEmpty(), roleBlock, statusBlock.isEmpty(), statusBlock);
    }

    private static boolean isProtectedRole(RoleRow role, Set<Permission> permissions) {
        return role.isOwner() || role.isAdmin() || permissions.contains(Permission.ADMIN_ROLES);
    }
    private Set<Permission> permissionsOf(RoleRow role) { return access.permissionsOf(role.id()); }

    private RoleRow roleByKind(String kind) {
        return jdbc.queryForObject("select " + RoleRow.COLUMNS + " from roles where system_kind=?", RoleRow::map, kind);
    }
    private OwnerRef ownerRef(long userId) {
        return jdbc.queryForObject("select id,display_name,email from user_accounts where id=?", (rs, n) -> new OwnerRef(rs.getLong(1), rs.getString(2), rs.getString(3)), userId);
    }

    private long currentVersion() { Long v = jdbc.queryForObject("select version from access_control_state where id=1", Long.class); return v == null ? 0 : v; }
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
    /** 只统计正常账号：停用账号不计入“正常管理者”（UA-R07）。 */
    private Map<Long, Long> activeCountsByRole() {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.query("select coalesce(role_id,(select d.id from roles d where d.system_kind='DEFAULT')) as role_id,count(*) as n from user_accounts where account_status='ACTIVE' group by coalesce(role_id,(select d.id from roles d where d.system_kind='DEFAULT'))",
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
    public record UserItem(long id, String displayName, String email, RoleRef role, boolean canManage, java.time.Instant createdAt,
                           String accountStatus, long statusVersion, boolean siteOwner, boolean canAssignRole, String roleBlockReason, boolean canChangeStatus, String statusBlockReason) {}
    public record UserPage(List<UserItem> items, int page, int size, long total, long managerCount) {}
    public record CatalogItem(String code, String group, String label, String detail, boolean lockAll, boolean lockAdmin, boolean guest) {}
    public record RoleItem(String code, String name, String description, String systemKind, List<String> permissions, long userCount, boolean assignable, boolean manageable) {}
    public record OwnerRef(long id, String displayName, String email) {}
    public record OwnershipView(OwnerRef owner, long version, List<OwnerRef> candidates) {}
    public record TransferOutcome(OwnerRef owner, RoleRef formerOwnerRole, long operatorEpoch) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record RolesView(long version, int maxRoles, List<CatalogItem> catalog, List<String> guest, List<RoleItem> roles, String createdCode) {}
    public record AuditItem(long id, String type, String action, java.time.Instant createdAt, String operatorName, String targetUserName,
                            String roleName, String beforeRoleName, String afterRoleName, String copyFromName, String moveToName,
                            List<String> addedPermissions, List<String> removedPermissions, Integer movedCount, List<String> movedNames, String note,
                            String beforeStatus, String afterStatus, String outcome, String resultMessage) {}
    public record AuditPage(List<AuditItem> items, int page, int size, long total) {}
}
