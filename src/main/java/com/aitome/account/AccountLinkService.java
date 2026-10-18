package com.aitome.account;

import com.aitome.access.AccessService;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.user.UserAccount;
import com.aitome.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.aitome.account.AccountTokenService.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 邮件链接流程：申请链接、检查链接、注册验证、设置账号、旧账号补验证（概要设计 §3.1/§3.5，契约 §4）。
 * 公开申请对所有情形返回同一结果，不暴露邮箱是否注册。
 */
@Service
public class AccountLinkService {
    public static final String UNIFIED_MESSAGE = "如果该邮箱可以接收账号邮件，我们会发送操作链接，请检查收件箱。";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    static final Duration IP_WINDOW = Duration.ofMinutes(10);
    static final int REQUEST_LIMIT = 10, TOKEN_LIMIT = 30;

    private final UserRepository users;
    private final AccessService access;
    private final BCryptPasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AccountTokenService tokens;
    private final AccountMailService mail;
    private final SlidingWindowLimiter limiter;
    private final Clock clock;

    public AccountLinkService(UserRepository users, AccessService access, BCryptPasswordEncoder encoder, JdbcTemplate jdbc, TransactionTemplate tx,
                              AccountTokenService tokens, AccountMailService mail, SlidingWindowLimiter limiter, Clock clock) {
        this.users = users; this.access = access; this.encoder = encoder; this.jdbc = jdbc; this.tx = tx;
        this.tokens = tokens; this.mail = mail; this.limiter = limiter; this.clock = clock;
    }

    public static String normalizeEmail(String value) {
        String email = value == null ? "" : value.trim().toLowerCase();
        if (email.isEmpty() || email.length() > 120 || !EMAIL.matcher(email).matches())
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "请输入完整邮箱，例如 name@example.com。");
        return email;
    }

    private static ApiProblem tooMany() { return new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "操作过于频繁，请稍后再试。"); }

    /** 公开申请（注册验证 / 忘记密码）。除格式错误和来源限流外，所有情形的结果相同。 */
    public void requestPublic(TokenPurpose purpose, String rawEmail, String ip) {
        String email = normalizeEmail(rawEmail);
        if (!limiter.tryAcquire("link-ip:" + ip, REQUEST_LIMIT, IP_WINDOW)) throw tooMany();
        Optional<UserAccount> user = users.findByEmailIgnoreCase(email);
        if (purpose == TokenPurpose.SIGNUP) { if (user.isPresent()) return; }
        else if (user.isEmpty()) return;
        issueAndSend(purpose, email, user.map(UserAccount::getId).orElse(null), user.map(u -> !u.isEmailVerified()).orElse(false));
    }

    /** 登录态申请（改密忘记原密码 / 补验证）。收件人始终是会话用户自己的邮箱；返回距下次可发送的剩余秒数。 */
    public int requestForUser(UserAccount user, TokenPurpose purpose) {
        if (!limiter.tryAcquire("link-user:" + user.getId(), REQUEST_LIMIT, IP_WINDOW)) throw tooMany();
        boolean needed = purpose != TokenPurpose.VERIFY || !user.isEmailVerified();
        if (needed) issueAndSend(purpose, user.getEmail(), user.getId(), !user.isEmailVerified());
        return tokens.cooldownSeconds(purpose, user.getEmail());
    }

    private void issueAndSend(TokenPurpose purpose, String email, Long userId, boolean unverifiedAccount) {
        tokens.issue(purpose, email, userId).ifPresent(issued -> mail.sendLink(issued, unverifiedAccount));
    }

    // —— 检查（只读，不消耗）——

    public record LinkView(String email, TokenPurpose purpose, java.time.Instant expiresAt, boolean verified, boolean needsVerification) {}

    public LinkView inspect(String raw, TokenPurpose purpose, String ip) {
        throttleToken(ip);
        Row row = purpose == TokenPurpose.SIGNUP ? tokens.requireLive(raw, purpose, ACTIVE, VERIFIED) : tokens.requireLive(raw, purpose, ACTIVE);
        boolean needs = false;
        if (purpose == TokenPurpose.RESET) needs = userOf(row).map(u -> !u.isEmailVerified()).orElseThrow(() -> gone(MSG_INVALID));
        return new LinkView(row.email(), purpose, row.expiresAt(), VERIFIED.equals(row.status()), needs);
    }

    // —— 注册：邮箱确认 → 设置账号 ——

    public String confirmSignupEmail(String raw, String ip) {
        throttleToken(ip);
        Row row = tokens.requireLive(raw, TokenPurpose.SIGNUP, ACTIVE, VERIFIED);
        if (ACTIVE.equals(row.status()) && !tokens.markVerified(row.id())) tokens.requireLive(raw, TokenPurpose.SIGNUP, VERIFIED); // 并发下已被确认则幂等
        return row.email();
    }

    public String register(String raw, String displayName, String password, String confirmation, String ip) {
        throttleToken(ip);
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 40) throw new ApiProblem(HttpStatus.BAD_REQUEST, "请填写40字以内的昵称。");
        PasswordPolicy.validateNew(password, confirmation);
        Row seen = tokens.requireLive(raw, TokenPurpose.SIGNUP, ACTIVE, VERIFIED);
        if (!VERIFIED.equals(seen.status())) throw new ApiProblem(HttpStatus.FORBIDDEN, "请先验证邮箱，再设置账号。");
        String hash = encoder.encode(password);
        return tx.execute(status -> {
            if (!tokens.consume(seen.id(), VERIFIED)) { tokens.requireLive(raw, TokenPurpose.SIGNUP, VERIFIED); throw gone(MSG_INVALID); }
            if (users.existsByEmailIgnoreCase(seen.email())) throw conflict();
            UserAccount account = UserAccount.verified(seen.email(), hash, name, clock.instant());
            account.assignRole(access.defaultRole().id()); // 角色只由服务端分配，请求中的任何角色字段都被忽略
            try { users.saveAndFlush(account); }
            catch (DataIntegrityViolationException duplicate) { throw conflict(); }
            tokens.supersedeEmailTokens(seen.email(), seen.id());
            return seen.email();
        });
    }

    private static ApiProblem conflict() { return new ApiProblem(HttpStatus.CONFLICT, "注册无法完成，请尝试登录或找回密码。"); }

    // —— 旧账号单独补验证：保留当前会话 ——

    public boolean verifyEmail(String raw, String ip) {
        throttleToken(ip);
        Row row = tokens.requireLive(raw, TokenPurpose.VERIFY, ACTIVE);
        long userId = row.userId() == null ? -1 : row.userId();
        return tx.execute(status -> {
            String email = lockedEmail(userId);
            if (email == null || !email.equalsIgnoreCase(row.email())) throw gone(MSG_INVALID);
            if (!tokens.consume(row.id(), ACTIVE)) { tokens.requireLive(raw, TokenPurpose.VERIFY, ACTIVE); throw gone(MSG_INVALID); }
            jdbc.update("update user_accounts set email_verified_at=? where id=? and email_verified_at is null", Timestamp.from(clock.instant()), userId);
            return Boolean.TRUE.equals(jdbc.queryForObject("select legacy_account from user_accounts where id=?", Boolean.class, userId));
        });
    }

    String lockedEmail(long userId) {
        return jdbc.query("select email from user_accounts where id=? for update", (rs, n) -> rs.getString(1), userId).stream().findFirst().orElse(null);
    }

    private Optional<UserAccount> userOf(Row row) { return row.userId() == null ? Optional.empty() : users.findById(row.userId()); }

    void throttleToken(String ip) { if (!limiter.tryAcquire("token-ip:" + ip, TOKEN_LIMIT, IP_WINDOW)) throw tooMany(); }
}
