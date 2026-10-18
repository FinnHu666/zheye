package com.aitome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FINNS-15 账号：邮箱验证、邮件找回、主动改密与会话撤销（契约 §10，T-A01–T-A24 的 MockMvc 部分）。
 * 邮件与时钟使用 TestAccountConfig 的替身；真实 SMTP、浏览器与 PostgreSQL 并发另有验证。
 */
@SpringBootTest @AutoConfigureMockMvc
class AccountFlowTests {
    static final String PW = "Passw0rd-12345";
    static final AtomicInteger COUNTER = new AtomicInteger();
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired com.aitome.account.AccountBootstrap bootstrap;
    @Autowired TestAccountConfig.MutableClock clock; @Autowired TestAccountConfig.RecordingMailSender mail;

    @BeforeEach void reset() { clock.reset(); mail.reset(); }

    // —— 辅助 ——

    String uniq(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@finns.test"; }

    ResultActions send(String path, MockHttpSession session, Object body, String ip) throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var request = post(path).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body == null ? Map.of() : body));
        if (session != null) request.session(session);
        String address = ip != null ? ip : "10.9." + (COUNTER.incrementAndGet() / 250) + "." + (COUNTER.get() % 250);
        request.with(r -> { r.setRemoteAddr(address); return r; });
        return mvc.perform(request);
    }
    ResultActions send(String path, MockHttpSession session, Object body) throws Exception { return send(path, session, body, null); }

    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    /** 完整的邮件注册流程，返回令牌。 */
    String registerViaMail(String email, String name, String password, Map<String, Object> extra) throws Exception {
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "signup")).andExpect(status().isOk());
        String token = mail.lastToken(email);
        send("/api/auth/link/inspect", null, Map.of("token", token, "purpose", "signup")).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(false));
        send("/api/auth/signup-verify", null, Map.of("token", token)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        Map<String, Object> request = new HashMap<>(Map.of("token", token, "displayName", name, "password", password, "confirmPassword", password));
        request.putAll(extra);
        send("/api/auth/register", null, request).andExpect(status().isCreated());
        return token;
    }

    MockHttpSession login(String email, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        send("/api/auth/login", session, Map.of("email", email, "password", password)).andExpect(status().isOk());
        return session;
    }

    MockHttpSession member(String prefix, String email) throws Exception {
        TestUsers.create(jdbc, email, PW, prefix);
        return login(email, PW);
    }

    long idOf(String email) { return jdbc.queryForObject("select id from user_accounts where email=?", Long.class, email); }
    long epochOf(String email) { return jdbc.queryForObject("select session_epoch from user_accounts where email=?", Long.class, email); }

    void legacyUser(String email, String password, boolean legacy) {
        jdbc.update("insert into user_accounts(email,password_hash,display_name,bio,created_at,role_id,legacy_account) values(?,?,?,?,?,(select id from roles where system_kind='DEFAULT'),?)",
                email, new BCryptPasswordEncoder(4).encode(password), "旧账号", "", Timestamp.from(Instant.now()), legacy);
    }

    // —— T-A01/A02/A08：注册 ——

    @Test void registrationVerifiesEmailFirstCreatesNoSessionAndIgnoresInjectedFields() throws Exception {
        String email = uniq("reg");
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "signup")).andExpect(status().isOk()).andExpect(jsonPath("$.cooldown").value(60));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts where email=?", Integer.class, email), "no account before verification");
        String token = mail.lastToken(email);
        send("/api/auth/link/inspect", null, Map.of("token", token, "purpose", "signup")).andExpect(status().isOk());
        send("/api/auth/link/inspect", null, Map.of("token", token, "purpose", "signup")).andExpect(status().isOk()); // 打开链接不消耗
        send("/api/auth/register", null, Map.of("token", token, "displayName", "新用户", "password", PW, "confirmPassword", PW)).andExpect(status().isForbidden()); // 未确认邮箱
        send("/api/auth/signup-verify", null, Map.of("token", token)).andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts where email=?", Integer.class, email), "verification alone creates no account");
        var result = send("/api/auth/register", null, Map.of("token", token, "displayName", "新用户", "password", PW, "confirmPassword", PW,
                "email", "admin@finns.test", "role", "ADMIN", "roleCode", "ADMIN", "userId", 1)).andExpect(status().isCreated()).andReturn();
        assertNull(result.getResponse().getCookie("JSESSIONID"), "registration must not create a login session");
        assertTrue(result.getRequest().getSession(false) == null || result.getRequest().getSession(false).getAttribute("USER_ID") == null);
        assertEquals("MEMBER", jdbc.queryForObject("select r.code from user_accounts u join roles r on r.id=u.role_id where u.email=?", String.class, email));
        assertNotNull(jdbc.queryForObject("select email_verified_at from user_accounts where email=?", Timestamp.class, email));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts where email='admin@finns.test'", Integer.class));
        send("/api/auth/register", null, Map.of("token", token, "displayName", "重复", "password", PW, "confirmPassword", PW)).andExpect(status().isGone());
        assertEquals(0, jdbc.queryForObject("select count(*) from account_action_tokens where token_hash=?", Integer.class, token), "raw token is never stored");
        MockHttpSession session = new MockHttpSession();
        send("/api/auth/login", session, Map.of("email", email, "password", PW)).andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true)).andExpect(jsonPath("$.role.code").value("MEMBER"));
    }

    @Test void registeringAnExistingEmailSendsNothingAndLooksTheSame() throws Exception {
        String existing = uniq("exists");
        TestUsers.create(jdbc, existing, PW, "已有");
        String fresh = uniq("fresh");
        JsonNode a = body(send("/api/auth/request-link", null, Map.of("email", existing, "purpose", "signup")).andExpect(status().isOk()));
        JsonNode b = body(send("/api/auth/request-link", null, Map.of("email", fresh, "purpose", "signup")).andExpect(status().isOk()));
        assertEquals(a.path("message"), b.path("message")); assertEquals(a.path("cooldown"), b.path("cooldown"));
        assertEquals(0, mail.to(existing).size());
        assertEquals(1, mail.to(fresh).size());
    }

    @Test void unverifiedNewAccountCannotLogInButLegacyUnverifiedCan() throws Exception {
        String fresh = uniq("fresh"), old = uniq("old");
        legacyUser(fresh, PW, false);
        legacyUser(old, PW, true);
        send("/api/auth/login", new MockHttpSession(), Map.of("email", fresh, "password", PW)).andExpect(status().isForbidden());
        send("/api/auth/login", new MockHttpSession(), Map.of("email", fresh, "password", "wrong-password")).andExpect(status().isUnauthorized()); // 未授权者探测不到验证状态
        send("/api/auth/login", new MockHttpSession(), Map.of("email", old, "password", PW)).andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(false));
    }

    // —— T-A04/A05/A18：重置 ——

    @Test void resetTokenIsViewableWithoutConsumingExpiresAndWorksOnce() throws Exception {
        String email = uniq("reset");
        MockHttpSession other = member("重置", email);
        long epoch = epochOf(email);
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset")).andExpect(status().isOk());
        String token = mail.lastToken(email);
        for (int i = 0; i < 3; i++) send("/api/auth/link/inspect", null, Map.of("token", token, "purpose", "reset")).andExpect(status().isOk()).andExpect(jsonPath("$.needsVerification").value(false));
        assertEquals(epoch, epochOf(email), "requesting and opening a link does not revoke sessions");
        mvcGet("/api/auth/me", other).andExpect(status().isOk());
        send("/api/auth/reset", null, Map.of("token", token, "password", "short", "confirmPassword", "short")).andExpect(status().isBadRequest());
        send("/api/auth/link/inspect", null, Map.of("token", token, "purpose", "reset")).andExpect(status().isOk()); // 校验失败不消耗
        send("/api/auth/reset", null, Map.of("token", token, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!", "userId", 999)).andExpect(status().isOk());
        mvcGet("/api/auth/me", other).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("登录已失效"));
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", PW)).andExpect(status().isUnauthorized());
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", "N3w-passw0rd!")).andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true));
        send("/api/auth/reset", null, Map.of("token", token, "password", "An0ther-pass!", "confirmPassword", "An0ther-pass!")).andExpect(status().isGone());
        assertTrue(mail.to(email).stream().anyMatch(m -> m.subject().contains("已更新")), "password-changed notice is sent");
        assertTrue(mail.to(email).stream().noneMatch(m -> m.text().contains("N3w-passw0rd!")), "mail never contains the password");
    }

    @Test void resetLinkExpiresAfterThirtyMinutes() throws Exception {
        String email = uniq("expire");
        TestUsers.create(jdbc, email, PW, "过期");
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset"));
        String token = mail.lastToken(email);
        clock.advance(Duration.ofMinutes(29));
        send("/api/auth/link/inspect", null, Map.of("token", token, "purpose", "reset")).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(2));
        send("/api/auth/reset", null, Map.of("token", token, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("链接已过期，请重新申请。"));
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", PW)).andExpect(status().isOk());
    }

    @Test void legacyUnverifiedAccountVerifiesAndResetsInOneMailAndSignsEveryoneOut() throws Exception {
        String email = uniq("legacy");
        legacyUser(email, PW, true);
        MockHttpSession session = login(email, PW);
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset")).andExpect(status().isOk());
        assertEquals(1, mail.to(email).size(), "one mail only");
        assertEquals("验证邮箱并重置密码", mail.to(email).get(0).subject());
        send("/api/auth/reset", null, Map.of("token", mail.lastToken(email), "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isOk());
        assertNotNull(jdbc.queryForObject("select email_verified_at from user_accounts where email=?", Timestamp.class, email));
        mvcGet("/api/auth/me", session).andExpect(status().isUnauthorized());
    }

    @Test void standaloneVerificationKeepsTheCurrentSession() throws Exception {
        String email = uniq("verify");
        legacyUser(email, PW, true);
        MockHttpSession session = login(email, PW);
        long epoch = epochOf(email);
        send("/api/account/verification-link", session, Map.of()).andExpect(status().isOk());
        send("/api/auth/verify", null, Map.of("token", mail.lastToken(email))).andExpect(status().isOk()).andExpect(jsonPath("$.legacy").value(true));
        assertEquals(epoch, epochOf(email));
        mvcGet("/api/auth/me", session).andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true));
        int before = mail.to(email).size();
        send("/api/account/verification-link", session, Map.of()).andExpect(status().isOk());
        assertEquals(before, mail.to(email).size(), "already verified: nothing sent");
    }

    // —— T-A06/A07：主动改密 ——

    @Test void activePasswordChangeKeepsThisSessionRevokesOthersAndNotifies() throws Exception {
        String email = uniq("change");
        TestUsers.create(jdbc, email, PW, "改密");
        MockHttpSession here = login(email, PW), elsewhere = login(email, PW);
        send("/api/account/password", here, Map.of("oldPassword", "wrong-password", "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isBadRequest());
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", PW)).andExpect(status().isOk());
        send("/api/account/password", here, Map.of("oldPassword", PW, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeFailed").value(false));
        mvcGet("/api/auth/me", here).andExpect(status().isOk());
        mvcGet("/api/auth/me", elsewhere).andExpect(status().isUnauthorized());
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", PW)).andExpect(status().isUnauthorized());
        assertTrue(mail.to(email).stream().anyMatch(m -> m.subject().contains("已更新")));
    }

    @Test void notificationFailureDoesNotRollBackTheChange() throws Exception {
        String email = uniq("notice");
        MockHttpSession session = member("通知", email);
        mail.failing = true;
        send("/api/account/password", session, Map.of("oldPassword", PW, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isOk())
                .andExpect(jsonPath("$.noticeFailed").value(true));
        mail.failing = false;
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", "N3w-passw0rd!")).andExpect(status().isOk());
    }

    @Test void repeatedWrongOldPasswordIsThrottledWithoutBlockingLogin() throws Exception {
        String email = uniq("throttle");
        MockHttpSession session = member("限流", email);
        for (int i = 0; i < 5; i++)
            send("/api/account/password", session, Map.of("oldPassword", "wrong-" + i, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isBadRequest());
        send("/api/account/password", session, Map.of("oldPassword", PW, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isTooManyRequests());
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", PW)).andExpect(status().isOk());
    }

    @Test void passwordChangeNeedsLoginAndIgnoresSuppliedIdentity() throws Exception {
        String email = uniq("owner"), victim = uniq("victim");
        MockHttpSession session = member("本人", email);
        TestUsers.create(jdbc, victim, PW, "他人");
        send("/api/account/password", null, Map.of("oldPassword", PW, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isUnauthorized());
        send("/api/account/password", session, Map.of("oldPassword", PW, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!", "userId", idOf(victim), "email", victim)).andExpect(status().isOk());
        send("/api/auth/login", new MockHttpSession(), Map.of("email", victim, "password", PW)).andExpect(status().isOk());
    }

    @Test void signedInResetRequestTargetsOwnEmailAndDoesNotChangeAnything() throws Exception {
        String email = uniq("self"), other = uniq("other");
        MockHttpSession session = member("自助", email);
        TestUsers.create(jdbc, other, PW, "他人");
        long epoch = epochOf(email);
        send("/api/account/password-reset-link", session, Map.of("email", other, "userId", idOf(other))).andExpect(status().isOk());
        assertEquals(1, mail.to(email).size()); assertEquals(0, mail.to(other).size());
        assertEquals(epoch, epochOf(email));
        mvcGet("/api/auth/me", session).andExpect(status().isOk());
        send("/api/account/password-reset-link", null, Map.of()).andExpect(status().isUnauthorized());
        send("/api/auth/reset", null, Map.of("token", mail.lastToken(email), "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!"), "10.8.0.1").andExpect(status().isOk());
        mvcGet("/api/auth/me", session).andExpect(status().isUnauthorized());
    }

    // —— T-A11/A12/A13：统一响应、冷却、替代 ——

    @Test void publicRequestsLookIdenticalWhetherOrNotAnythingWasSent() throws Exception {
        String known = uniq("known"), unknown = uniq("unknown");
        TestUsers.create(jdbc, known, PW, "已知");
        List<JsonNode> responses = new ArrayList<>();
        responses.add(body(send("/api/auth/request-link", null, Map.of("email", known, "purpose", "reset")).andExpect(status().isOk())));
        responses.add(body(send("/api/auth/request-link", null, Map.of("email", known, "purpose", "reset")).andExpect(status().isOk()))); // 冷却内
        responses.add(body(send("/api/auth/request-link", null, Map.of("email", unknown, "purpose", "reset")).andExpect(status().isOk())));
        clock.advance(Duration.ofSeconds(61)); mail.failing = true;
        responses.add(body(send("/api/auth/request-link", null, Map.of("email", known, "purpose", "reset")).andExpect(status().isOk()))); // 发送失败
        for (JsonNode r : responses) assertEquals(responses.get(0), r);
        assertEquals(1, mail.to(known).size()); assertEquals(0, mail.to(unknown).size());
        send("/api/auth/request-link", null, Map.of("email", "not-an-email", "purpose", "reset")).andExpect(status().isBadRequest());
        send("/api/auth/request-link", null, Map.of("email", known, "purpose", "verify")).andExpect(status().isBadRequest());
    }

    @Test void resendRespectsCooldownReplacesOldLinkAndFailureKeepsIt() throws Exception {
        String email = uniq("resend");
        TestUsers.create(jdbc, email, PW, "重发");
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset"));
        String first = mail.lastToken(email);
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset"));
        assertEquals(1, mail.to(email).size(), "within 60 seconds nothing more is sent");
        clock.advance(Duration.ofSeconds(61)); mail.failNextOnly = true;
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset"));
        assertEquals(1, mail.to(email).size());
        send("/api/auth/link/inspect", null, Map.of("token", first, "purpose", "reset")).andExpect(status().isOk()); // 发送失败：旧链接仍有效
        clock.advance(Duration.ofSeconds(61));
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset"));
        String second = mail.lastToken(email);
        assertNotEquals(first, second);
        send("/api/auth/link/inspect", null, Map.of("token", first, "purpose", "reset")).andExpect(status().isGone());
        send("/api/auth/link/inspect", null, Map.of("token", second, "purpose", "reset")).andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("select count(*) from account_action_tokens where email=? and status='ACTIVE'", Integer.class, email));
    }

    @Test void hourlyAttemptCapAppliesEvenWhenSendsFail() throws Exception {
        String email = uniq("cap");
        TestUsers.create(jdbc, email, PW, "上限");
        mail.failing = true;
        for (int i = 0; i < 6; i++) { send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset")).andExpect(status().isOk()); clock.advance(Duration.ofSeconds(61)); }
        assertEquals(5, jdbc.queryForObject("select count(*) from account_action_tokens where email=?", Integer.class, email));
    }

    @Test void signupResendInvalidatesTheVerifiedLinkAndFailedSendKeepsIt() throws Exception {
        String email = uniq("signup");
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "signup"));
        String first = mail.lastToken(email);
        send("/api/auth/signup-verify", null, Map.of("token", first)).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(61)); mail.failing = true;
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "signup"));
        send("/api/auth/link/inspect", null, Map.of("token", first, "purpose", "signup")).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        mail.failing = false; clock.advance(Duration.ofSeconds(61));
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "signup"));
        send("/api/auth/register", null, Map.of("token", first, "displayName", "旧链接", "password", PW, "confirmPassword", PW)).andExpect(status().isGone());
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts where email=?", Integer.class, email));
    }

    @Test void signupLinkExpiresAfterTwentyFourHours() throws Exception {
        String email = uniq("day");
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "signup"));
        String token = mail.lastToken(email);
        send("/api/auth/signup-verify", null, Map.of("token", token)).andExpect(status().isOk());
        clock.advance(Duration.ofHours(25));
        send("/api/auth/register", null, Map.of("token", token, "displayName", "过期", "password", PW, "confirmPassword", PW)).andExpect(status().isGone());
    }

    // —— T-P01/P02：密码规则 ——

    @Test void newPasswordBoundariesAreEightToTwentyCodePointsAndNeverTruncatedForBcrypt() throws Exception {
        String email = uniq("bounds");
        MockHttpSession session = member("边界", email);
        String current = PW;
        for (int n : new int[]{7, 21}) {
            String bad = "a".repeat(n);
            send("/api/account/password", session, Map.of("oldPassword", current, "password", bad, "confirmPassword", bad)).andExpect(status().isBadRequest());
        }
        String fourByte = "😀".repeat(20); // 20 个码点 = 80 个 UTF-8 字节，超过 BCrypt 的 72 字节
        send("/api/account/password", session, Map.of("oldPassword", current, "password", fourByte, "confirmPassword", fourByte)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("密码包含较多特殊字符，请适当缩短。"));
        send("/api/account/password", session, Map.of("oldPassword", current, "password", "N3w-passw0rd!", "confirmPassword", "different-one")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("两次输入的密码不一致。"));
        for (String ok : new String[]{"a".repeat(8), "b".repeat(20), "密".repeat(20), "😀".repeat(18)}) {
            send("/api/account/password", session, Map.of("oldPassword", current, "password", ok, "confirmPassword", ok)).andExpect(status().isOk());
            current = ok;
        }
        send("/api/auth/login", new MockHttpSession(), Map.of("email", email, "password", current)).andExpect(status().isOk());
    }

    @Test void bcryptSilentlyIgnoresBytesBeyondSeventyTwoWhichIsWhyThePolicyRejectsThem() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String seventyTwo = "a".repeat(72);
        assertTrue(encoder.matches(seventyTwo + "DIFFERENT", encoder.encode(seventyTwo)), "documents the upstream truncation (Spring Security 6.3.6)");
        assertNotNull(PasswordPolicyProbe.reject("😀".repeat(19)), "19 code points but 76 bytes");
    }

    @Test void bootstrapMarksExistingUnverifiedAccountsOnceAndNeverVerifiesThem() {
        String before = uniq("before"), after = uniq("after");
        jdbc.update("delete from account_state"); // 模拟升级前的库：尚未执行过旧账号过渡
        legacyUser(before, PW, false);
        bootstrap.initialize();
        assertTrue(jdbc.queryForObject("select legacy_account from user_accounts where email=?", Boolean.class, before));
        assertNull(jdbc.queryForObject("select email_verified_at from user_accounts where email=?", Timestamp.class, before), "history mailboxes are never batch-verified");
        legacyUser(after, PW, false);
        bootstrap.initialize(); // 标记已存在：之后创建的未验证账号不会被当作旧账号
        assertFalse(jdbc.queryForObject("select legacy_account from user_accounts where email=?", Boolean.class, after));
    }

    // —— 横切 ——

    @Test void publicAccountPostsStillRequireCsrf() throws Exception {
        mvc.perform(post("/api/auth/request-link").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.co\",\"purpose\":\"reset\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/reset").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test void oldOneStepRegistrationIsGone() throws Exception {
        send("/api/auth/register", null, Map.of("email", uniq("old"), "password", PW, "displayName", "旧接口")).andExpect(status().is4xxClientError());
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts where display_name='旧接口'", Integer.class));
    }

    @Test void ipRateLimitAppliesToEveryEmailAlike() throws Exception {
        String ip = "ratelimit-" + UUID.randomUUID();
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            int status = send("/api/auth/request-link", null, Map.of("email", uniq("ip"), "purpose", "reset"), ip).andReturn().getResponse().getStatus();
            if (status == 429) limited++;
        }
        assertEquals(2, limited);
    }

    // —— 并发 ——

    @Test void concurrentResetsOfTheSameLinkSucceedExactlyOnce() throws Exception {
        String email = uniq("race");
        TestUsers.create(jdbc, email, PW, "并发");
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset"));
        String token = mail.lastToken(email);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String password = "Race-passw0rd-" + i;
            results.add(pool.submit(() -> { go.await(); return send("/api/auth/reset", null, Map.of("token", token, "password", password, "confirmPassword", password)).andReturn().getResponse().getStatus(); }));
        }
        go.countDown();
        int ok = 0, gone = 0;
        for (Future<Integer> f : results) { int s = f.get(30, TimeUnit.SECONDS); if (s == 200) ok++; else if (s == 410) gone++; }
        pool.shutdownNow();
        assertEquals(1, ok); assertEquals(threads - 1, gone);
    }

    @Test void concurrentRegistrationsWithTheSameLinkCreateExactlyOneAccount() throws Exception {
        String email = uniq("regrace");
        send("/api/auth/request-link", null, Map.of("email", email, "purpose", "signup"));
        String token = mail.lastToken(email);
        send("/api/auth/signup-verify", null, Map.of("token", token)).andExpect(status().isOk());
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            results.add(pool.submit(() -> { go.await(); return send("/api/auth/register", null, Map.of("token", token, "displayName", "并发" + n, "password", PW, "confirmPassword", PW)).andReturn().getResponse().getStatus(); }));
        }
        go.countDown();
        int created = 0;
        for (Future<Integer> f : results) if (f.get(30, TimeUnit.SECONDS) == 201) created++;
        pool.shutdownNow();
        assertEquals(1, created);
        assertEquals(1, jdbc.queryForObject("select count(*) from user_accounts where email=?", Integer.class, email));
    }

    ResultActions mvcGet(String path, MockHttpSession session) throws Exception { return mvc.perform(get(path).session(session)); }

    /** 直接探测策略（不经 HTTP），避免测试里重复写 Unicode 逻辑。 */
    static final class PasswordPolicyProbe {
        static String reject(String password) { return com.aitome.account.PasswordPolicy.check(password, password); }
    }
}
