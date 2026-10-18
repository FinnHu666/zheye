package com.aitome.account;

import jakarta.persistence.*;
import java.time.Instant;

/** 单行标记表映射：旧账号过渡（legacy_account）是否已执行。 */
@Entity
@Table(name = "account_state")
public class AccountState {
    @Id private Short id;
    @Column(name = "legacy_marked_at", nullable = false) private Instant legacyMarkedAt;

    protected AccountState() {}
}
