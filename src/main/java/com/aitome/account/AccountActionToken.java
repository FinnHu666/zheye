package com.aitome.account;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 账号操作令牌表的映射。业务读写使用 JdbcTemplate 的条件更新（见 {@link AccountTokenService}），
 * 此实体只用于让 H2 开发库按 ddl update 建表、并让 PostgreSQL 的 Hibernate validate 校验 V13 的结构。
 */
@Entity
@Table(name = "account_action_tokens")
public class AccountActionToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 10) private String purpose;
    @Column(nullable = false, length = 120) private String email;
    @Column(name = "user_id") private Long userId;
    @Column(name = "token_hash", nullable = false, length = 64, unique = true) private String tokenHash;
    @Column(nullable = false, length = 10) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "verified_at") private Instant verifiedAt;
    @Column(name = "used_at") private Instant usedAt;

    protected AccountActionToken() {}
}
