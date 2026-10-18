package com.aitome.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_accounts", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120) private String email;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false, length = 40) private String displayName;
    @Column(nullable = false, length = 500) private String bio = "";
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    /** 单一角色（FINNS-11）。只由服务端分配；PostgreSQL 中为 not null，由 V12 迁移补齐现有用户。 */
    @Column(name = "role_id") private Long roleId;
    /** 邮箱归属验证时间（FINNS-15）。为空表示尚未验证；新注册账号创建时即已验证。 */
    @Column(name = "email_verified_at") private Instant emailVerifiedAt;
    /** V13 迁移时已存在的账号：允许在未验证时继续登录并提示补验证。 */
    @Column(name = "legacy_account", nullable = false, columnDefinition = "boolean default false not null") private boolean legacyAccount;
    /** 会话撤销版本：登录时写入会话，不一致即视为已失效。只通过 SQL 自增，不由实体写回。 */
    @Column(name = "session_epoch", nullable = false, insertable = false, updatable = false, columnDefinition = "bigint default 0 not null") private long sessionEpoch;
    @Column(name = "password_changed_at") private Instant passwordChangedAt;
    /** 账号状态（FINNS-16）：ACTIVE / DISABLED。只通过 AccessAdminService 的 SQL 变更，不由实体写回。 */
    @Column(name = "account_status", nullable = false, insertable = false, updatable = false, columnDefinition = "varchar(10) default 'ACTIVE' not null") private String accountStatus = "ACTIVE";
    @Column(name = "status_version", nullable = false, insertable = false, updatable = false, columnDefinition = "bigint default 1 not null") private long statusVersion;

    protected UserAccount() {}
    public UserAccount(String email, String passwordHash, String displayName) {
        this.email = email.toLowerCase(); this.passwordHash = passwordHash; this.displayName = displayName;
    }
    /** 通过邮箱验证流程创建的账号（FINNS-15）：创建即已验证，不属于旧账号过渡。 */
    public static UserAccount verified(String email, String passwordHash, String displayName, Instant verifiedAt) {
        UserAccount account = new UserAccount(email, passwordHash, displayName);
        account.emailVerifiedAt = verifiedAt;
        return account;
    }
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public Instant getCreatedAt() { return createdAt; }
    public void updateProfile(String displayName, String bio) { this.displayName = displayName; this.bio = bio; }
    public Long getRoleId() { return roleId; }
    public void assignRole(Long roleId) { this.roleId = roleId; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }
    public boolean isLegacyAccount() { return legacyAccount; }
    public long getSessionEpoch() { return sessionEpoch; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public boolean isActive() { return !"DISABLED".equals(accountStatus); }
    public long getStatusVersion() { return statusVersion; }
}
