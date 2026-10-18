package com.aitome.account;

import java.time.Duration;
import java.util.Optional;

/** 账号操作令牌用途。注册/补验证链接 24 小时有效，重置链接（含旧账号验证并重置）30 分钟有效（ACCOUNT-D05）。 */
public enum TokenPurpose {
    SIGNUP(Duration.ofHours(24)), VERIFY(Duration.ofHours(24)), RESET(Duration.ofMinutes(30));

    private final Duration lifetime;
    TokenPurpose(Duration lifetime) { this.lifetime = lifetime; }
    public Duration lifetime() { return lifetime; }
    public String param() { return name().toLowerCase(); }

    public static Optional<TokenPurpose> fromParam(String value) {
        if (value == null) return Optional.empty();
        for (TokenPurpose purpose : values()) if (purpose.param().equals(value.trim().toLowerCase())) return Optional.of(purpose);
        return Optional.empty();
    }
}
