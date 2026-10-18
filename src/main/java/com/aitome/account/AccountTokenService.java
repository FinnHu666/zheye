package com.aitome.account;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 一次性账号令牌的状态机（概要设计 §3.1）。原始令牌只在邮件和发送任务内存里出现，库中只存 SHA-256。
 * 消耗一律是条件更新（状态 + 到期时间），影响行数为 1 才算成功，因此并发提交只有一次生效。
 */
@Service
public class AccountTokenService {
    public static final String PENDING = "PENDING", ACTIVE = "ACTIVE", VERIFIED = "VERIFIED", USED = "USED", SUPERSEDED = "SUPERSEDED", FAILED = "FAILED";
    public static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    static final Duration PENDING_STALE = Duration.ofMinutes(2);
    static final Duration HOUR = Duration.ofHours(1);
    static final int HOURLY_ATTEMPTS = 5;
    static final Duration RETENTION = Duration.ofDays(7);

    public static final String MSG_INVALID = "链接无效或已被新链接替代，请重新申请。";
    public static final String MSG_USED = "这条链接已经使用，请重新申请。";
    public static final String MSG_EXPIRED = "链接已过期，请重新申请。";

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AccountTokenService(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) { this.jdbc = jdbc; this.tx = tx; this.clock = clock; }

    public record Issued(long id, String raw, TokenPurpose purpose, String email, Long userId) {}
    public record Row(long id, TokenPurpose purpose, String email, Long userId, String status, Instant expiresAt) {}

    public static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** 邮箱的短摘要，用于日志（不记录邮箱明文）。 */
    public static String emailTag(String email) { return hash(email.toLowerCase()).substring(0, 12); }

    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }

    /**
     * 创建一条 PENDING 令牌。同邮箱同用途 60 秒内已发送、1 小时内已尝试 5 次、仍有在途发送时返回 empty（调用方按统一响应处理）。
     */
    public Optional<Issued> issue(TokenPurpose purpose, String email, Long userId) {
        Instant now = clock.instant();
        return tx.execute(status -> {
            jdbc.update("update account_action_tokens set status='FAILED' where email=? and purpose=? and status='PENDING' and created_at<?",
                    email, purpose.name(), ts(now.minus(PENDING_STALE)));
            Integer pending = jdbc.queryForObject("select count(*) from account_action_tokens where email=? and purpose=? and status='PENDING'", Integer.class, email, purpose.name());
            if (pending != null && pending > 0) return Optional.empty();
            Integer recent = jdbc.queryForObject("select count(*) from account_action_tokens where email=? and purpose=? and sent_at>?", Integer.class, email, purpose.name(), ts(now.minus(SEND_INTERVAL)));
            if (recent != null && recent > 0) return Optional.empty();
            Integer hour = jdbc.queryForObject("select count(*) from account_action_tokens where email=? and purpose=? and created_at>?", Integer.class, email, purpose.name(), ts(now.minus(HOUR)));
            if (hour != null && hour >= HOURLY_ATTEMPTS) return Optional.empty();
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                jdbc.update("insert into account_action_tokens(purpose,email,user_id,token_hash,status,created_at,expires_at) values(?,?,?,?,?,?,?)",
                        purpose.name(), email, userId, hash(raw), PENDING, ts(now), ts(now.plus(purpose.lifetime())));
            } catch (DuplicateKeyException concurrent) {
                status.setRollbackOnly(); // PostgreSQL 在唯一约束失败后事务已不可用，显式回滚
                return Optional.empty();
            }
            Long id = jdbc.queryForObject("select id from account_action_tokens where token_hash=?", Long.class, hash(raw));
            jdbc.update("delete from account_action_tokens where id in (select id from account_action_tokens where expires_at<? order by id limit 100)", ts(now.minus(RETENTION)));
            return Optional.of(new Issued(id, raw, purpose, email, userId));
        });
    }

    /** 发送成功：同用途旧的有效链接作废，本令牌生效。令牌已被替代/失败时不做任何事。 */
    public void activate(Issued issued) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            Integer pending = jdbc.queryForObject("select count(*) from account_action_tokens where id=? and status='PENDING'", Integer.class, issued.id());
            if (pending == null || pending == 0) return;
            jdbc.update("update account_action_tokens set status='SUPERSEDED' where email=? and purpose=? and status in ('ACTIVE','VERIFIED') and id<>?",
                    issued.email(), issued.purpose().name(), issued.id());
            jdbc.update("update account_action_tokens set status='ACTIVE', sent_at=? where id=? and status='PENDING'", ts(now), issued.id());
        });
    }

    /** 发送失败：只标记本令牌，仍有效的旧链接保持不变。 */
    public void fail(long id) {
        jdbc.update("update account_action_tokens set status='FAILED' where id=? and status='PENDING'", id);
    }

    /** 同邮箱同用途距下一次可发送的剩余秒数（仅用于登录态响应，公开响应恒为 60）。 */
    public int cooldownSeconds(TokenPurpose purpose, String email) {
        List<Timestamp> sent = jdbc.query("select max(sent_at) from account_action_tokens where email=? and purpose=?", (rs, n) -> rs.getTimestamp(1), email, purpose.name());
        if (sent.isEmpty() || sent.get(0) == null) return 0;
        long remaining = Duration.between(clock.instant(), sent.get(0).toInstant().plus(SEND_INTERVAL)).toMillis();
        return remaining <= 0 ? 0 : (int) Math.ceil(remaining / 1000.0);
    }

    /** 按原文查找并校验：存在、用途一致、未被替代/使用、未过期，且状态在允许集合内。否则 410（固定文案）。 */
    public Row requireLive(String raw, TokenPurpose purpose, String... allowedStatuses) {
        if (raw == null || raw.isBlank() || raw.length() > 200) throw gone(MSG_INVALID);
        List<Row> rows = jdbc.query("select id,purpose,email,user_id,status,expires_at from account_action_tokens where token_hash=? and purpose=?",
                (rs, n) -> new Row(rs.getLong("id"), TokenPurpose.valueOf(rs.getString("purpose")), rs.getString("email"),
                        (Long) rs.getObject("user_id"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()),
                hash(raw), purpose.name());
        if (rows.isEmpty()) throw gone(MSG_INVALID);
        Row row = rows.get(0);
        if (USED.equals(row.status())) throw gone(MSG_USED);
        boolean allowed = false;
        for (String status : allowedStatuses) allowed |= status.equals(row.status());
        if (!allowed) throw gone(MSG_INVALID);
        if (!clock.instant().isBefore(row.expiresAt())) throw gone(MSG_EXPIRED);
        return row;
    }

    /** 条件消耗：状态与到期时间同时满足才成功。必须在调用方事务内使用。 */
    public boolean consume(long id, String fromStatus) {
        Instant now = clock.instant();
        return jdbc.update("update account_action_tokens set status='USED', used_at=? where id=? and status=? and expires_at>?", ts(now), id, fromStatus, ts(now)) == 1;
    }

    /** 注册邮箱已确认：ACTIVE → VERIFIED。 */
    public boolean markVerified(long id) {
        Instant now = clock.instant();
        return jdbc.update("update account_action_tokens set status='VERIFIED', verified_at=? where id=? and status='ACTIVE' and expires_at>?", ts(now), id, ts(now)) == 1;
    }

    /** 密码更新或注册完成后：撤销该用户其余未使用的恢复/验证链接（含在途发送）。 */
    public void supersedeUserTokens(long userId, long exceptId) {
        jdbc.update("update account_action_tokens set status='SUPERSEDED' where user_id=? and purpose in ('RESET','VERIFY') and status in ('PENDING','ACTIVE','VERIFIED') and id<>?", userId, exceptId);
    }

    public void supersedeEmailTokens(String email, long exceptId) {
        jdbc.update("update account_action_tokens set status='SUPERSEDED' where email=? and status in ('PENDING','ACTIVE','VERIFIED') and id<>?", email, exceptId);
    }

    static ApiProblem gone(String message) { return new ApiProblem(HttpStatus.GONE, message); }
}
