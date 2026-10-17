package com.aitome.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 写入操作记录（与业务变更处于同一事务）。名称为写入时快照。 */
@Component
public class AccessAuditWriter {
    private final JdbcTemplate jdbc;

    public AccessAuditWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void assign(Long operatorId, String operatorName, long targetId, String targetName,
                       String beforeCode, String beforeName, String afterCode, String afterName, String note) {
        jdbc.update("insert into access_audit_log(type,operator_id,operator_name,target_user_id,target_user_name,before_role_code,before_role_name,after_role_code,after_role_name,note,created_at) values('ROLE_ASSIGN',?,?,?,?,?,?,?,?,?,?)",
                operatorId, operatorName, targetId, targetName, beforeCode, beforeName, afterCode, afterName, clean(note), now());
    }

    public void permissions(long operatorId, String operatorName, RoleRow role, Collection<Permission> added, Collection<Permission> removed, String note) {
        jdbc.update("insert into access_audit_log(type,operator_id,operator_name,role_id,role_code,role_name,added_permissions,removed_permissions,note,created_at) values('ROLE_PERMISSION',?,?,?,?,?,?,?,?,?)",
                operatorId, operatorName, role.id(), role.code(), role.name(), codes(added), codes(removed), clean(note), now());
    }

    public void created(long operatorId, String operatorName, RoleRow role, RoleRow copyFrom, String note) {
        jdbc.update("insert into access_audit_log(type,action,operator_id,operator_name,role_id,role_code,role_name,copy_from_code,copy_from_name,note,created_at) values('ROLE_DEFINE','CREATE',?,?,?,?,?,?,?,?,?)",
                operatorId, operatorName, role.id(), role.code(), role.name(), copyFrom == null ? null : copyFrom.code(), copyFrom == null ? null : copyFrom.name(), clean(note), now());
    }

    public void updated(long operatorId, String operatorName, RoleRow before, String afterName, String note) {
        jdbc.update("insert into access_audit_log(type,action,operator_id,operator_name,role_id,role_code,role_name,before_role_name,after_role_name,note,created_at) values('ROLE_DEFINE','UPDATE',?,?,?,?,?,?,?,?,?)",
                operatorId, operatorName, before.id(), before.code(), afterName, before.name(), afterName, clean(note), now());
    }

    public void deleted(long operatorId, String operatorName, RoleRow role, RoleRow moveTo, List<String> movedNames, int movedCount, String note) {
        jdbc.update("insert into access_audit_log(type,action,operator_id,operator_name,role_id,role_code,role_name,move_to_code,move_to_name,moved_count,moved_names,note,created_at) values('ROLE_DEFINE','DELETE',?,?,?,?,?,?,?,?,?,?,?)",
                operatorId, operatorName, role.id(), role.code(), role.name(), moveTo == null ? null : moveTo.code(), moveTo == null ? null : moveTo.name(),
                movedCount, String.join("、", movedNames), clean(note), now());
    }

    private static String codes(Collection<Permission> permissions) {
        return permissions.isEmpty() ? null : String.join(",", permissions.stream().map(Permission::code).toList());
    }
    private static String clean(String note) { return note == null ? "" : note.trim().substring(0, Math.min(200, note.trim().length())); }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
}
