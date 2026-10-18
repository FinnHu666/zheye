package com.aitome.account;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static com.aitome.account.AccountTokenService.*;

/**
 * 修改密码的两条路径（ACCOUNT-D02）：
 * 主动改密核对原密码，保留当前设备、其他设备退出；邮件重置凭一次性令牌，成功后全部设备退出。
 * 两者都在用户行锁内更新哈希并使 session_epoch + 1，并撤销该账号其余未用恢复链接。
 */
@Service
public class AccountPasswordService {
    static final Duration OLD_PASSWORD_WINDOW = Duration.ofMinutes(15);
    static final int OLD_PASSWORD_FAILURES = 5;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder encoder;
    private final AccountTokenService tokens;
    private final AccountLinkService links;
    private final AccountMailService mail;
    private final SlidingWindowLimiter limiter;
    private final Clock clock;

    public AccountPasswordService(JdbcTemplate jdbc, TransactionTemplate tx, BCryptPasswordEncoder encoder, AccountTokenService tokens,
                                  AccountLinkService links, AccountMailService mail, SlidingWindowLimiter limiter, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.encoder = encoder; this.tokens = tokens; this.links = links; this.mail = mail; this.limiter = limiter; this.clock = clock;
    }

    public record Result(boolean noticeFailed, long newEpoch) {}

    /** 邮件重置：旧账号同时完成邮箱验证；成功后所有会话失效，不自动登录。 */
    public Result reset(String raw, String password, String confirmation, String ip) {
        links.throttleToken(ip);
        Row row = tokens.requireLive(raw, TokenPurpose.RESET, ACTIVE);
        PasswordPolicy.validateNew(password, confirmation); // 校验失败不消耗令牌
        long userId = row.userId() == null ? -1 : row.userId();
        String hash = encoder.encode(password);
        String email = tx.execute(status -> {
            String locked = links.lockedEmail(userId);
            if (locked == null || !locked.equalsIgnoreCase(row.email())) throw gone(MSG_INVALID);
            if (!tokens.consume(row.id(), ACTIVE)) { tokens.requireLive(raw, TokenPurpose.RESET, ACTIVE); throw gone(MSG_INVALID); }
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("update user_accounts set password_hash=?, password_changed_at=?, email_verified_at=coalesce(email_verified_at,?), session_epoch=session_epoch+1 where id=?",
                    hash, now, now, userId);
            tokens.supersedeUserTokens(userId, row.id());
            return locked;
        });
        long epoch = epochOf(userId);
        return new Result(!mail.sendPasswordChangedNotice(email), epoch);
    }

    /** 主动改密。调用方负责把当前会话的 AUTH_EPOCH 更新为返回的新值。 */
    public Result change(long userId, String oldPassword, String password, String confirmation) {
        String key = "old-password:" + userId;
        if (limiter.count(key, OLD_PASSWORD_WINDOW) >= OLD_PASSWORD_FAILURES)
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "尝试过于频繁，请稍后再试，或通过邮箱重置密码。");
        PasswordPolicy.validateNew(password, confirmation);
        if (oldPassword == null || oldPassword.isEmpty()) throw new ApiProblem(HttpStatus.BAD_REQUEST, "请输入原密码。");
        String hash = encoder.encode(password);
        Object[] done = tx.execute(status -> {
            List<String[]> rows = jdbc.query("select email,password_hash from user_accounts where id=? for update", (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, userId);
            if (rows.isEmpty()) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "登录已失效");
            if (!encoder.matches(oldPassword, rows.get(0)[1])) {
                limiter.record(key);
                throw new ApiProblem(HttpStatus.BAD_REQUEST, "原密码不正确，密码未改变。");
            }
            jdbc.update("update user_accounts set password_hash=?, password_changed_at=?, session_epoch=session_epoch+1 where id=?", hash, Timestamp.from(clock.instant()), userId);
            tokens.supersedeUserTokens(userId, -1);
            return new Object[]{rows.get(0)[0], epochOf(userId)}; // 行锁内读取，避免被并发的撤销抬高
        });
        limiter.clear(key);
        return new Result(!mail.sendPasswordChangedNotice((String) done[0]), (Long) done[1]);
    }

    private long epochOf(long userId) {
        Long epoch = jdbc.queryForObject("select session_epoch from user_accounts where id=?", Long.class, userId);
        return epoch == null ? 0 : epoch;
    }
}
