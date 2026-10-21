package com.aitome.review;

import jakarta.persistence.*;
import java.time.Instant;

/** 敏感规则表映射（由 SensitiveRuleService 通过 JDBC 读写；实体仅用于 H2 建表与 PostgreSQL 结构校验）。 */
@Entity
@Table(name = "sensitive_rules")
public class SensitiveRuleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 40) private String phrase;
    @Column(nullable = false, length = 200, columnDefinition = "varchar(200) default '' not null") private String note = "";
    @Column(nullable = false, columnDefinition = "boolean default true not null") private boolean enabled = true;
    @Column(nullable = false, columnDefinition = "boolean default false not null") private boolean deleted;
    @Column(name = "active_key", length = 160, unique = true) private String activeKey;
    @Column(name = "match_mode", nullable = false, length = 10, columnDefinition = "varchar(10) default 'SUBSTRING' not null") private String matchMode = "SUBSTRING";
    @Column(nullable = false, length = 8, columnDefinition = "varchar(8) default 'MEDIUM' not null") private String level = "MEDIUM";
    @Column(length = 30) private String tag;
    @Column(nullable = false, columnDefinition = "bigint default 1 not null") private long version = 1;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected SensitiveRuleEntity() {}
}
