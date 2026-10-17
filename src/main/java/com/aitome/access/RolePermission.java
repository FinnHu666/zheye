package com.aitome.access;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

/** 角色拥有的一项权限。PostgreSQL 由 V12 建表；H2 由此实体建表。 */
@Entity
@Table(name = "role_permissions")
@IdClass(RolePermission.Key.class)
public class RolePermission {
    @Id @Column(name = "role_id") private Long roleId;
    @Id @Column(length = 40) private String permission;

    protected RolePermission() {}
    public RolePermission(Long roleId, String permission) { this.roleId = roleId; this.permission = permission; }

    public static class Key implements Serializable {
        private Long roleId; private String permission;
        public Key() {}
        @Override public boolean equals(Object o) { return o instanceof Key k && Objects.equals(roleId, k.roleId) && Objects.equals(permission, k.permission); }
        @Override public int hashCode() { return Objects.hash(roleId, permission); }
    }
}
