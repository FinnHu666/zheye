package com.aitome.access;

import jakarta.persistence.*;
import java.time.Instant;

/** 操作记录。名称均为写入时的快照：角色改名或删除后，历史仍显示当时的名称。由 AccessAdminService 通过 JDBC 写入。 */
@Entity
@Table(name = "access_audit_log", indexes = {
        @Index(name = "idx_audit_created", columnList = "created_at,id"),
        @Index(name = "idx_audit_type_created", columnList = "type,created_at")
})
public class AccessAuditEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 20) private String type;
    @Column(length = 10) private String action;
    @Column(name = "operator_id") private Long operatorId;
    @Column(name = "operator_name", nullable = false, length = 40) private String operatorName;
    @Column(name = "target_user_id") private Long targetUserId;
    @Column(name = "target_user_name", length = 40) private String targetUserName;
    @Column(name = "role_id") private Long roleId;
    @Column(name = "role_code", length = 40) private String roleCode;
    @Column(name = "role_name", length = 20) private String roleName;
    @Column(name = "before_role_code", length = 40) private String beforeRoleCode;
    @Column(name = "before_role_name", length = 20) private String beforeRoleName;
    @Column(name = "after_role_code", length = 40) private String afterRoleCode;
    @Column(name = "after_role_name", length = 20) private String afterRoleName;
    @Column(name = "copy_from_code", length = 40) private String copyFromCode;
    @Column(name = "copy_from_name", length = 20) private String copyFromName;
    @Column(name = "move_to_code", length = 40) private String moveToCode;
    @Column(name = "move_to_name", length = 20) private String moveToName;
    @Column(name = "added_permissions", length = 500) private String addedPermissions;
    @Column(name = "removed_permissions", length = 500) private String removedPermissions;
    @Column(name = "moved_count") private Integer movedCount;
    @Column(name = "moved_names", length = 500) private String movedNames;
    @Column(name = "before_status", length = 10) private String beforeStatus;
    @Column(name = "after_status", length = 10) private String afterStatus;
    @Column(nullable = false, length = 10, columnDefinition = "varchar(10) default 'SUCCESS' not null") private String outcome = "SUCCESS";
    @Column(name = "result_message", length = 200) private String resultMessage;
    @Column(nullable = false, length = 200) private String note = "";
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected AccessAuditEntry() {}
}
