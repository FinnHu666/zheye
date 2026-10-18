package com.aitome;

import com.aitome.AccessTestSupport.Account;
import com.aitome.access.AccessAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FINNS-16 账号停用与恢复（契约 §4.2、§5、§10；验收 UA-A01–A10）。独立数据库。
 * “最后一位正常管理者”的兜底规则在站长永不被停用的前提下无法经接口触达，这里验证它的口径（停用账号不计入）。
 */
// 默认独立 H2；设置 FINNS_USER_ADMIN_TEST_DB_URL（及 _FLYWAY=true、_DDL=validate）可对隔离 PostgreSQL 库运行同一套测试，见实施记录。
@SpringBootTest(properties = {"spring.datasource.url=${FINNS_USER_ADMIN_TEST_DB_URL:jdbc:h2:mem:aitome-user-status;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.flyway.enabled=${FINNS_USER_ADMIN_TEST_FLYWAY:false}", "spring.jpa.hibernate.ddl-auto=${FINNS_USER_ADMIN_TEST_DDL:create-drop}"})
@AutoConfigureMockMvc
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserStatusTests {
    static final String PW = "password123";
    static final AtomicInteger COUNTER = new AtomicInteger();
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired TestAccountConfig.RecordingMailSender mail;
    AccessTestSupport t;
    static Account owner, admin, operator;

    @BeforeEach void setUp() throws Exception {
        t = new AccessTestSupport(mvc, json, jdbc);
        mail.reset();
        if (owner == null) {
            owner = t.withRole("owner", "OWNER");
            admin = t.withRole("admin", "ADMIN");
            // 只有 admin.users 的自定义角色：不是“受保护角色”，可以管理普通成员
            jdbc.update("insert into roles(code,name,description,system_kind,sort_order,created_at,updated_at) values('UMGR','用户运营','',null,50,current_timestamp,current_timestamp)");
            long role = jdbc.queryForObject("select id from roles where code='UMGR'", Long.class);
            for (String p : List.of("content.public", "profile.manage", "admin.users", "admin.audit")) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", role, p);
            operator = t.withRole("umgr", "UMGR");
        }
    }

    @Test @Order(1) void listShowsStatusFiltersCombineAndRejectUnknownStatus() throws Exception {
        Account a = t.register("filter"), b = t.register("filter");
        disable(owner, b.id(), "停用用于筛选").andExpect(status().isOk());
        JsonNode all = list(owner, Map.of("q", "filter", "size", "50"));
        assertEquals(2, all.path("total").asInt());
        assertEquals("ACTIVE", item(all, a.id()).path("accountStatus").asText());
        assertEquals("DISABLED", item(all, b.id()).path("accountStatus").asText());
        JsonNode disabledOnly = list(owner, Map.of("q", "filter", "status", "DISABLED", "size", "50"));
        assertEquals(1, disabledOnly.path("total").asInt());
        assertEquals(b.id(), disabledOnly.path("items").get(0).path("id").asLong());
        assertEquals(0, list(owner, Map.of("q", "filter", "status", "DISABLED", "role", "TRUSTED")).path("total").asInt(), "status combines with role");
        assertEquals(1, list(owner, Map.of("q", "filter", "status", "ACTIVE", "role", "MEMBER", "size", "1", "page", "0")).path("items").size());
        mvc.perform(get("/api/admin/users").param("status", "BOGUS").session(owner.session())).andExpect(status().isBadRequest());
        // 非站长看到的提示：站长/管理员行不可停用
        JsonNode viewedByUm = list(operator, Map.of("size", "50"));
        assertFalse(item(viewedByUm, owner.id()).path("canChangeStatus").asBoolean());
        assertFalse(item(viewedByUm, admin.id()).path("canChangeStatus").asBoolean());
        assertTrue(item(viewedByUm, a.id()).path("canChangeStatus").asBoolean());
        assertTrue(item(viewedByUm, owner.id()).path("siteOwner").asBoolean());
    }

    @Test @Order(2) void disableNeedsAReasonKeepsTheAccountAndIsIdempotent() throws Exception {
        Account m = t.register("reason");
        long version = item(list(owner, Map.of("q", m.email())), m.id()).path("statusVersion").asLong();
        for (String bad : new String[]{"", "   ", null, "字".repeat(201)}) {
            Map<String, Object> body = new HashMap<>(Map.of("status", "DISABLED", "expectedVersion", version));
            if (bad != null) body.put("note", bad);
            patchStatus(owner, m.id(), body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请填写1至200字的操作原因。"));
        }
        patchStatus(owner, m.id(), Map.of("status", "SUSPENDED", "expectedVersion", version, "note", "x")).andExpect(status().isBadRequest());
        patchStatus(owner, 99999999L, Map.of("status", "DISABLED", "expectedVersion", version, "note", "x")).andExpect(status().isNotFound());
        assertEquals("ACTIVE", statusOf(m.id()), "failed attempts changed nothing");

        JsonNode done = body(disable(owner, m.id(), "  违反社区规范  ", version).andExpect(status().isOk()));
        assertEquals("DISABLED", done.path("accountStatus").asText());
        assertEquals(version + 1, done.path("statusVersion").asLong());
        assertEquals("MEMBER", done.path("role").path("code").asText(), "role is kept");
        assertEquals(m.name(), done.path("displayName").asText(), "profile is kept");
        assertEquals("违反社区规范", jdbc.queryForObject("select note from access_audit_log where type='ACCOUNT_STATUS' and target_user_id=? and outcome='SUCCESS'", String.class, m.id()), "reason is trimmed and recorded");
        // 重复提交：目标状态已达成，不重复生效也不重复记录
        long epoch = epochOf(m.id());
        disable(owner, m.id(), "再次提交", version).andExpect(status().isOk()).andExpect(jsonPath("$.accountStatus").value("DISABLED")).andExpect(jsonPath("$.statusVersion").value(version + 1));
        assertEquals(epoch, epochOf(m.id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from access_audit_log where type='ACCOUNT_STATUS' and target_user_id=? and outcome='SUCCESS'", Integer.class, m.id()));
        // 过期版本：其他管理者已经恢复，再按旧版本停用要求核对
        restore(owner, m.id(), "误操作恢复", version + 1).andExpect(status().isOk());
        disable(owner, m.id(), "按旧版本", version).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_STATUS_CONFLICT));
        assertEquals("ACTIVE", statusOf(m.id()));
    }

    @Test @Order(3) void disabledAccountsCannotLogInOrUseOldSessionsAndOnlyTheRightPasswordSeesTheNotice() throws Exception {
        Account m = t.register("lock");
        MockHttpSession second = new MockHttpSession();
        TestUsers.login(mvc, second, m.email(), PW).andExpect(status().isOk());
        String secret = "内部备注-不应出现给本人";
        disable(owner, m.id(), secret).andExpect(status().isOk());

        for (MockHttpSession s : List.of(m.session(), second)) {
            mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized());
            mvc.perform(t.write(post("/api/posts"), s, Map.of("type", "TOPIC", "title", "t", "summary", "s", "body", "b"))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/account/security").session(s)).andExpect(status().isUnauthorized());
        }
        String blocked = TestUsers.login(mvc, new MockHttpSession(), m.email(), PW).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("账号已停用，请联系站点管理员。")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(blocked.contains(secret), "the management reason is never shown to the account holder");
        String wrong = TestUsers.login(mvc, new MockHttpSession(), m.email(), "wrong-password").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String unknown = TestUsers.login(mvc, new MockHttpSession(), "nobody-" + UUID.randomUUID() + "@finns.test", PW).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(wrong, unknown, "wrong password and unknown email stay indistinguishable");
        assertFalse(wrong.contains("停用"));
        mvc.perform(t.write(post("/api/auth/logout"), m.session(), null)).andExpect(status().isNoContent()); // 退出仍可执行

        restore(owner, m.id(), "申诉成立").andExpect(status().isOk()).andExpect(jsonPath("$.accountStatus").value("ACTIVE"));
        for (MockHttpSession s : List.of(second)) mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized()); // 旧会话不复活
        MockHttpSession fresh = new MockHttpSession();
        TestUsers.login(mvc, fresh, m.email(), PW).andExpect(status().isOk()).andExpect(jsonPath("$.role.code").value("MEMBER"));
        mvc.perform(get("/api/auth/me").session(fresh)).andExpect(status().isOk());
    }

    @Test @Order(4) void passwordRecoveryWorksWhileDisabledButNeverLiftsTheDisable() throws Exception {
        Account m = t.register("recover");
        disable(owner, m.id(), "核查中").andExpect(status().isOk());
        long epoch = epochOf(m.id());
        publicPost("/api/auth/request-link", Map.of("email", m.email(), "purpose", "reset")).andExpect(status().isOk());
        String token = mail.lastToken(m.email());
        publicPost("/api/auth/reset", Map.of("token", token, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isOk());
        assertEquals("DISABLED", statusOf(m.id()), "reset does not lift the disable");
        assertTrue(epochOf(m.id()) > epoch);
        mvc.perform(get("/api/auth/me").session(m.session())).andExpect(status().isUnauthorized());
        TestUsers.login(mvc, new MockHttpSession(), m.email(), "N3w-passw0rd!").andExpect(status().isForbidden()); // 密码正确，仍是停用
        restore(owner, m.id(), "核查完毕").andExpect(status().isOk());
        TestUsers.login(mvc, new MockHttpSession(), m.email(), "N3w-passw0rd!").andExpect(status().isOk());
        TestUsers.login(mvc, new MockHttpSession(), m.email(), PW).andExpect(status().isUnauthorized());
    }

    @Test @Order(5) void publishedWorksKeepTheirVisibilityWhileTheAuthorIsDisabled() throws Exception {
        Account author = t.register("author");
        long post = body(mvc.perform(t.write(post("/api/posts"), author.session(), Map.of("type", "TOPIC", "title", "停用后仍可见", "summary", "s", "body", "b"))).andExpect(status().isCreated())).path("id").asLong();
        mvc.perform(get("/api/posts/" + post)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("停用后仍可见"));
        disable(owner, author.id(), "账号核查").andExpect(status().isOk());
        mvc.perform(get("/api/posts/" + post)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("停用后仍可见"));
        assertTrue(mvc.perform(get("/api/posts")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).contains("停用后仍可见"));
        restore(owner, author.id(), "核查完毕").andExpect(status().isOk());
        mvc.perform(get("/api/posts/" + post)).andExpect(status().isOk());
    }

    @Test @Order(6) void onlyAuthorizedOperatorsCanChangeStatusAndFailuresChangeNothing() throws Exception {
        Account target = t.register("guarded");
        long version = item(list(owner, Map.of("q", target.email())), target.id()).path("statusVersion").asLong();
        Map<String, Object> req = Map.of("status", "DISABLED", "expectedVersion", version, "note", "越权测试");
        mvc.perform(t.write(patch("/api/admin/users/" + target.id() + "/status"), null, req)).andExpect(status().isUnauthorized());
        Account plain = t.register("plain");
        mvc.perform(t.write(patch("/api/admin/users/" + target.id() + "/status"), plain.session(), req)).andExpect(status().isForbidden());
        Account revoked = t.withRole("revoked", "UMGR");
        t.setRole(revoked.id(), "MEMBER"); // 管理权限已被撤销
        mvc.perform(t.write(patch("/api/admin/users/" + target.id() + "/status"), revoked.session(), req)).andExpect(status().isForbidden());
        // 缺失或无效的 CSRF
        mvc.perform(patch("/api/admin/users/" + target.id() + "/status").session(owner.session()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/" + target.id() + "/status").session(owner.session()).cookie(new Cookie("XSRF-TOKEN", "x")).header("X-XSRF-TOKEN", "y")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req))).andExpect(status().isForbidden());
        assertEquals("ACTIVE", statusOf(target.id()));
        // 越级：运营角色不能处理管理员和站长；管理员也不能处理其他管理员/站长
        disable(operator, admin.id(), "越级").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_OTHER_ADMIN));
        disable(operator, owner.id(), "越级").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_OWNER_TARGET));
        disable(admin, owner.id(), "越级").andExpect(status().isForbidden());
        assertEquals("ACTIVE", statusOf(admin.id()));
        assertEquals("ACTIVE", statusOf(owner.id()));
        // 被授权的运营角色可以处理普通成员
        disable(operator, target.id(), "运营停用").andExpect(status().isOk());
        restore(operator, target.id(), "运营恢复").andExpect(status().isOk());
    }

    @Test @Order(7) void selfDisableIsRejectedAndDisabledManagersDoNotCount() throws Exception {
        disable(owner, owner.id(), "不能停用自己").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_SELF_DISABLE));
        disable(operator, operator.id(), "不能停用自己").andExpect(status().isUnprocessableEntity());
        Account extra = t.withRole("extra", "ADMIN");
        long before = list(owner, Map.of("size", "1")).path("managerCount").asLong();
        disable(owner, extra.id(), "管理员离职").andExpect(status().isOk());
        assertEquals(before - 1, list(owner, Map.of("size", "1")).path("managerCount").asLong(), "disabled managers are not normal managers");
        restore(owner, extra.id(), "返聘").andExpect(status().isOk());
        assertEquals(before, list(owner, Map.of("size", "1")).path("managerCount").asLong());
    }

    @Test @Order(8) void concurrentIdenticalAndCompetingChangesStayConsistent() throws Exception {
        Account m = t.register("race");
        Account second = t.withRole("race-um", "UMGR");
        long version = item(list(owner, Map.of("q", m.email())), m.id()).path("statusVersion").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 6; round++) {
                CountDownLatch go = new CountDownLatch(1);
                long v = item(list(owner, Map.of("q", m.email())), m.id()).path("statusVersion").asLong();
                boolean disabling = "ACTIVE".equals(statusOf(m.id()));
                List<Future<Integer>> calls = new ArrayList<>();
                for (Account actor : List.of(owner, second, operator, admin)) calls.add(pool.submit(() -> {
                    go.await();
                    Map<String, Object> req = Map.of("status", disabling ? "DISABLED" : "ACTIVE", "expectedVersion", v, "note", "并发");
                    return mvc.perform(t.write(patch("/api/admin/users/" + m.id() + "/status"), actor.session(), req)).andReturn().getResponse().getStatus();
                }));
                go.countDown();
                int ok = 0; for (Future<Integer> f : calls) { int s = f.get(30, TimeUnit.SECONDS); if (s == 200) ok++; else assertEquals(409, s, "unexpected status"); }
                assertTrue(ok >= 1, "one request must win");
                assertEquals(disabling ? "DISABLED" : "ACTIVE", statusOf(m.id()));
                assertEquals(round + 1, jdbc.queryForObject("select count(*) from access_audit_log where type='ACCOUNT_STATUS' and target_user_id=? and outcome='SUCCESS'", Integer.class, m.id()),
                        "exactly one success record per effective change, however many requests raced");
            }
        } finally { pool.shutdownNow(); }
        assertTrue(jdbc.queryForObject("select count(*) from user_accounts u join role_permissions rp on rp.role_id=u.role_id and rp.permission='admin.roles' where u.account_status='ACTIVE'", Integer.class) >= 1);
        assertEquals(version + 6, item(list(owner, Map.of("q", m.email())), m.id()).path("statusVersion").asLong(), "each round changed the status exactly once");
    }

    @Test @Order(9) void everyOutcomeIsRecordedWithoutCredentialsAndFailuresChangeNothing() throws Exception {
        Account m = t.register("audit");
        long version = item(list(owner, Map.of("q", m.email())), m.id()).path("statusVersion").asLong();
        disable(owner, m.id(), "记录成功", version).andExpect(status().isOk());
        disable(operator, admin.id(), "记录拒绝").andExpect(status().isForbidden());
        // 保存失败：数据库层拒绝这次写入，状态、会话都不变，仍留下失败记录和可重试的 503
        Account victim = t.register("auditfail");
        jdbc.execute("alter table user_accounts add constraint zz_block_disable check (account_status <> 'DISABLED' or id <> " + victim.id() + ")");
        try {
            long epoch = epochOf(victim.id());
            disable(owner, victim.id(), "记录失败").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_STATUS_UNAVAILABLE));
            assertEquals("ACTIVE", statusOf(victim.id()));
            assertEquals(epoch, epochOf(victim.id()), "no partial change: the session epoch was not raised");
            mvc.perform(get("/api/auth/me").session(victim.session())).andExpect(status().isOk());
        } finally { jdbc.execute("alter table user_accounts drop constraint zz_block_disable"); }

        JsonNode log = body(mvc.perform(get("/api/admin/audit").param("type", "ACCOUNT_STATUS").param("size", "50").session(owner.session())).andExpect(status().isOk()));
        Map<String, JsonNode> byNote = new HashMap<>();
        log.path("items").forEach(i -> byNote.put(i.path("note").asText(), i));
        JsonNode ok = byNote.get("记录成功"), rejected = byNote.get("记录拒绝"), failed = byNote.get("记录失败");
        assertEquals("SUCCESS", ok.path("outcome").asText());
        assertEquals("ACTIVE", ok.path("beforeStatus").asText()); assertEquals("DISABLED", ok.path("afterStatus").asText());
        assertEquals("DISABLE", ok.path("action").asText());
        assertEquals(owner.name(), ok.path("operatorName").asText()); assertEquals(m.name(), ok.path("targetUserName").asText());
        assertEquals("REJECTED", rejected.path("outcome").asText());
        assertEquals(AccessAdminService.MSG_OTHER_ADMIN, rejected.path("resultMessage").asText());
        assertEquals("ACTIVE", rejected.path("afterStatus").asText(), "a rejected request leaves the status unchanged");
        assertEquals("FAILED", failed.path("outcome").asText());
        assertEquals("ACTIVE", failed.path("afterStatus").asText());
        String raw = log.toString();
        assertFalse(raw.contains("password") || raw.contains("passwordHash") || raw.contains("token"), "no credentials in the record");
        // 只有 admin.audit 的人可以查看：普通成员不可
        mvc.perform(get("/api/admin/audit").session(t.register("nosee").session())).andExpect(status().isForbidden());
        assertEquals(1, body(mvc.perform(get("/api/admin/audit").param("type", "ACCOUNT_STATUS").param("q", "记录拒绝").session(operator.session()))).path("total").asInt());
    }

    @Test @Order(10) void theStatusItselfIsEnforcedEvenIfTheSessionEpochWasNotRaised() throws Exception {
        // 防御纵深：直接改库（例如恢复备份、人工处置）不抬高 session_epoch，停用状态本身也必须拦住旧会话与登录
        Account m = t.register("raw");
        mvc.perform(get("/api/auth/me").session(m.session())).andExpect(status().isOk());
        long epoch = epochOf(m.id());
        jdbc.update("update user_accounts set account_status='DISABLED' where id=?", m.id());
        assertEquals(epoch, epochOf(m.id()));
        mvc.perform(get("/api/auth/me").session(m.session())).andExpect(status().isUnauthorized());
        mvc.perform(t.write(post("/api/posts"), m.session(), Map.of("type", "TOPIC", "title", "t", "summary", "s", "body", "b"))).andExpect(status().isUnauthorized());
        TestUsers.login(mvc, new MockHttpSession(), m.email(), PW).andExpect(status().isForbidden());
        jdbc.update("update user_accounts set account_status='ACTIVE' where id=?", m.id());
        mvc.perform(get("/api/auth/me").session(m.session())).andExpect(status().isOk());
        // 只经权限拦截器（不走 requireUser）的接口：管理接口同样按停用状态拒绝
        Account manager = t.withRole("rawmgr", "UMGR");
        mvc.perform(get("/api/admin/users").session(manager.session())).andExpect(status().isOk());
        jdbc.update("update user_accounts set account_status='DISABLED' where id=?", manager.id());
        mvc.perform(get("/api/admin/users").session(manager.session())).andExpect(status().isUnauthorized());
        jdbc.update("update user_accounts set account_status='ACTIVE' where id=?", manager.id());
    }

    // —— 辅助 ——

    ResultActions disable(Account actor, long id, String note) throws Exception {
        return disable(actor, id, note, item(list(actor, Map.of("q", emailOf(id))), id).path("statusVersion").asLong());
    }
    ResultActions disable(Account actor, long id, String note, long version) throws Exception {
        return patchStatus(actor, id, Map.of("status", "DISABLED", "expectedVersion", version, "note", note));
    }
    ResultActions restore(Account actor, long id, String note) throws Exception {
        return restore(actor, id, note, item(list(actor, Map.of("q", emailOf(id))), id).path("statusVersion").asLong());
    }
    ResultActions restore(Account actor, long id, String note, long version) throws Exception {
        return patchStatus(actor, id, Map.of("status", "ACTIVE", "expectedVersion", version, "note", note));
    }
    ResultActions patchStatus(Account actor, long id, Map<String, Object> body) throws Exception {
        return mvc.perform(t.write(patch("/api/admin/users/" + id + "/status"), actor.session(), body));
    }
    JsonNode list(Account actor, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/users").session(actor.session());
        params.forEach(request::param);
        return body(mvc.perform(request).andExpect(status().isOk()));
    }
    static JsonNode item(JsonNode page, long id) {
        for (JsonNode i : page.path("items")) if (i.path("id").asLong() == id) return i;
        throw new AssertionError("user not in page: " + id);
    }
    String emailOf(long id) { return jdbc.queryForObject("select email from user_accounts where id=?", String.class, id); }
    String statusOf(long id) { return jdbc.queryForObject("select account_status from user_accounts where id=?", String.class, id); }
    long epochOf(long id) { return jdbc.queryForObject("select session_epoch from user_accounts where id=?", Long.class, id); }
    JsonNode body(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    ResultActions publicPost(String path, Object body) throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        String address = "10.8." + (COUNTER.incrementAndGet() / 250) + "." + (COUNTER.get() % 250);
        return mvc.perform(post(path).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .with(r -> { r.setRemoteAddr(address); return r; }));
    }
}
