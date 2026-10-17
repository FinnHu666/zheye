package com.aitome.access;

import jakarta.persistence.*;
import java.time.Instant;

/** 角色。系统种类 ADMIN（管理员）与 DEFAULT（注册默认）不可删除，可改名。 */
@Entity
@Table(name = "roles")
public class Role {
    public static final String KIND_ADMIN = "ADMIN";
    public static final String KIND_DEFAULT = "DEFAULT";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 40) private String code;
    @Column(nullable = false, length = 20) private String name;
    @Column(nullable = false, length = 60) private String description = "";
    @Column(name = "system_kind", length = 12) private String systemKind;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected Role() {}
    public Role(String code, String name, String description, String systemKind, int sortOrder) {
        this.code = code; this.name = name; this.description = description; this.systemKind = systemKind; this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSystemKind() { return systemKind; }
    public int getSortOrder() { return sortOrder; }
    public void rename(String name, String description) { this.name = name; this.description = description; this.updatedAt = Instant.now(); }
}
