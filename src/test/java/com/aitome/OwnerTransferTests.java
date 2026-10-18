package com.aitome;

import com.aitome.AccessTestSupport.Account;
import com.aitome.access.AccessAdminService;
import com.aitome.access.AccessBootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FINNS-16 站长层级、角色边界与站长转交（契约 §3、§4.4–§4.6、§7；验收 UA-A13–A15）。独立数据库。
 */
// 默认独立 H2；设置 FINNS_USER_ADMIN_TEST_DB_URL（及 _FLYWAY=true、_DDL=validate）可对隔离 PostgreSQL 库运行同一套测试，见实施记录。
@SpringBootTest(properties = {"spring.datasource.url=${FINNS_USER_ADMIN_TEST_DB_URL:jdbc:h2:mem:aitome-owner-transfer;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.flyway.enabled=${FINNS_USER_ADMIN_TEST_FLYWAY:false}", "spring.jpa.hibernate.ddl-auto=${FINNS_USER_ADMIN_TEST_DDL:create-drop}"})
@AutoConfigureMockMvc
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OwnerTransferTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired AccessBootstrap bootstrap;
    AccessTestSupport t;
    static Account owner, admin1, admin2, disabledAdmin, member, operator;

    @BeforeEach void setUp() throws Exception {
        t = new AccessTestSupport(mvc, json, jdbc);
        if (owner != null) return;
        owner = t.withRole("owner", "OWNER");
        admin1 = t.withRole("adminone", "ADMIN");
        admin2 = t.withRole("admintwo", "ADMIN");
        disabledAdmin = t.withRole("gone", "ADMIN");
        member = t.register("member");
        jdbc.update("insert into roles(code,name,description,system_kind,sort_order,created_at,updated_at) values('UMGR','用户运营','',null,50,current_timestamp,current_timestamp)");
        long role = jdbc.queryForObject("select id from roles where code='UMGR'", Long.class);
        for (String p : List.of("content.public", "profile.manage", "admin.users", "admin.audit")) jdbc.update("insert into role_permissions(role_id,permission) values(?,?)", role, p);
        operator = t.withRole("umgr", "UMGR");
        jdbc.update("update user_accounts set account_status='DISABLED', session_epoch=session_epoch+1 where id=?", disabledAdmin.id());
    }

    // —— 层级矩阵（UA-A13） ——

    @Test @Order(1) void adminsManageOnlyOrdinaryMembersAndOwnersAreUntouchable() throws Exception {
        // 管理员：可停用/恢复/调整普通成员为非管理角色（停用会使该账号的旧会话失效，所以状态变更用专用目标，保留 member 的会话）
        Account victim = t.register("victim");
        disable(admin1, victim.id()).andExpect(status().isOk());
        restore(admin1, victim.id()).andExpect(status().isOk());
        assign(admin1, member.id(), "TRUSTED").andExpect(status().isOk());
        assign(admin1, member.id(), "MEMBER").andExpect(status().isOk());
        // 管理员：不能授予管理员/站长，不能管理其他管理员、站长或自己的角色
        assign(admin1, member.id(), "ADMIN").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("只有站长可以授予管理员身份或管理角色权限。"));
        assign(admin1, member.id(), "OWNER").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_OWNER_UNIQUE));
        disable(admin1, admin2.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_OTHER_ADMIN));
        assign(admin1, admin2.id(), "MEMBER").andExpect(status().isForbidden());
        assign(admin1, admin1.id(), "MEMBER").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_SELF_ROLE));
        disable(admin1, owner.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_OWNER_TARGET));
        assign(admin1, owner.id(), "MEMBER").andExpect(status().isForbidden());
        // 运营角色同样受限
        assign(operator, member.id(), "ADMIN").andExpect(status().isForbidden());
        disable(operator, admin1.id()).andExpect(status().isForbidden());
        // 站长：可任免管理员、停用恢复管理员；不能分配站长、不能改自己的角色、不能停用自己
        assign(owner, member.id(), "ADMIN").andExpect(status().isOk());
        disable(owner, victim.id()).andExpect(status().isOk());
        restore(owner, victim.id()).andExpect(status().isOk());
        assign(owner, member.id(), "MEMBER").andExpect(status().isOk());
        assign(owner, member.id(), "OWNER").andExpect(status().isForbidden());
        assign(owner, owner.id(), "ADMIN").andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_OWNER_TARGET));
        disable(owner, owner.id()).andExpect(status().isUnprocessableEntity());
        disable(owner, admin2.id()).andExpect(status().isOk());
        restore(owner, admin2.id()).andExpect(status().isOk());
        assertOneOwner(owner.id());
        // 提示字段与服务端规则一致
        JsonNode viewedByAdmin = users(admin1);
        assertFalse(find(viewedByAdmin, admin2.id()).path("canAssignRole").asBoolean());
        assertTrue(find(viewedByAdmin, member.id()).path("canAssignRole").asBoolean());
        assertTrue(find(users(owner), admin2.id()).path("canChangeStatus").asBoolean());
        assertTrue(find(users(owner), owner.id()).path("siteOwner").asBoolean());
    }

    @Test @Order(2) void roleDefinitionsRespectTheOwnerLayerAndTheOwnerRoleIsFixed() throws Exception {
        long v = rolesVersion(owner);
        JsonNode created = body(roleCall(post("/api/admin/roles/definitions"), owner, Map.of("version", v, "name", "区域管理", "description", "", "copyFrom", "ADMIN")).andExpect(status().isCreated()));
        String managerRole = created.path("createdCode").asText(); // 复制管理员得到含 admin.roles 的“受保护”角色
        roleCall(post("/api/admin/roles/definitions"), owner, Map.of("version", rolesVersion(owner), "name", "复制站长", "copyFrom", "OWNER")).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("站长角色不能复制。"));
        roleCall(post("/api/admin/roles/definitions"), admin1, Map.of("version", rolesVersion(admin1), "name", "复制管理员", "copyFrom", "ADMIN")).andExpect(status().isForbidden());
        roleCall(post("/api/admin/roles/definitions"), admin1, Map.of("version", rolesVersion(admin1), "name", "复制区域", "copyFrom", managerRole)).andExpect(status().isForbidden());
        roleCall(post("/api/admin/roles/definitions"), admin1, Map.of("version", rolesVersion(admin1), "name", "普通副本", "copyFrom", "TRUSTED")).andExpect(status().isCreated());

        // 管理员视图：受保护角色不可分配/管理，普通角色可以
        JsonNode viewByAdmin = roles(admin1), viewByOwner = roles(owner);
        assertFalse(roleItem(viewByAdmin, "ADMIN").path("assignable").asBoolean());
        assertFalse(roleItem(viewByAdmin, managerRole).path("manageable").asBoolean());
        assertTrue(roleItem(viewByAdmin, "TRUSTED").path("manageable").asBoolean());
        assertTrue(roleItem(viewByOwner, "ADMIN").path("assignable").asBoolean());
        assertFalse(roleItem(viewByOwner, "OWNER").path("assignable").asBoolean());
        assertEquals("OWNER", roleItem(viewByOwner, "OWNER").path("systemKind").asText());

        // 权限修改：非站长不能改受保护角色、不能授予 admin.*；站长角色权限不可关闭
        List<String> trusted = perms(viewByAdmin, "TRUSTED");
        List<String> withAudit = new ArrayList<>(trusted); withAudit.add("admin.audit");
        putPerms(admin1, Map.of("TRUSTED", withAudit)).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("只有站长可以授予管理权限。"));
        List<String> managerPerms = new ArrayList<>(perms(viewByAdmin, managerRole)); managerPerms.remove("ai.use");
        putPerms(admin1, Map.of(managerRole, managerPerms)).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("只有站长可以调整管理员角色的权限。"));
        List<String> ownerWithout = new ArrayList<>(perms(viewByOwner, "OWNER")); ownerWithout.remove("ai.use");
        putPerms(owner, Map.of("OWNER", ownerWithout)).andExpect(status().isUnprocessableEntity());
        putPerms(owner, Map.of(managerRole, managerPerms)).andExpect(status().isOk());
        List<String> trustedMinus = new ArrayList<>(trusted); trustedMinus.remove("ai.use");
        putPerms(admin1, Map.of("TRUSTED", trustedMinus)).andExpect(status().isOk());
        putPerms(owner, Map.of("TRUSTED", trusted)).andExpect(status().isOk());

        // 改名/删除：站长角色固定；受保护角色仅站长；站长不能经删除/迁移改变
        roleCall(patch("/api/admin/roles/definitions/OWNER"), owner, Map.of("version", rolesVersion(owner), "name", "所有者")).andExpect(status().isForbidden());
        roleCall(patch("/api/admin/roles/definitions/ADMIN"), admin1, Map.of("version", rolesVersion(admin1), "name", "管理员改名")).andExpect(status().isForbidden());
        roleCall(patch("/api/admin/roles/definitions/" + managerRole), admin1, Map.of("version", rolesVersion(admin1), "name", "区域改名")).andExpect(status().isForbidden());
        roleCall(delete("/api/admin/roles/definitions/OWNER"), owner, Map.of("version", rolesVersion(owner))).andExpect(status().isForbidden());
        roleCall(delete("/api/admin/roles/definitions/ADMIN"), owner, Map.of("version", rolesVersion(owner))).andExpect(status().isUnprocessableEntity());
        roleCall(delete("/api/admin/roles/definitions/" + managerRole), admin1, Map.of("version", rolesVersion(admin1))).andExpect(status().isForbidden());
        String x = body(roleCall(post("/api/admin/roles/definitions"), owner, Map.of("version", rolesVersion(owner), "name", "迁移源")).andExpect(status().isCreated())).path("createdCode").asText();
        assign(owner, member.id(), x).andExpect(status().isOk());
        roleCall(delete("/api/admin/roles/definitions/" + x), owner, Map.of("version", rolesVersion(owner), "moveTo", "OWNER")).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("站长身份不能通过删除角色或迁移用户改变。"));
        roleCall(delete("/api/admin/roles/definitions/" + x), admin1, Map.of("version", rolesVersion(admin1), "moveTo", managerRole)).andExpect(status().isForbidden());
        assertEquals(x, roleOf(member.id()), "rejected deletions moved nobody");
        roleCall(delete("/api/admin/roles/definitions/" + x), admin1, Map.of("version", rolesVersion(admin1), "moveTo", "MEMBER")).andExpect(status().isOk());
        assertEquals("MEMBER", roleOf(member.id()));
        roleCall(delete("/api/admin/roles/definitions/" + managerRole), owner, Map.of("version", rolesVersion(owner))).andExpect(status().isOk());
        assertOneOwner(owner.id());
    }

    // —— 转交（UA-A14） ——

    @Test @Order(3) void onlyTheOwnerSeesCandidatesAndTheyAreLiveNormalAdmins() throws Exception {
        mvc.perform(get("/api/admin/ownership")).andExpect(status().isUnauthorized());
        for (Account a : List.of(admin1, operator)) mvc.perform(get("/api/admin/ownership").session(a.session())).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("只有当前站长可以转交站长身份。"));
        mvc.perform(get("/api/admin/ownership").session(member.session())).andExpect(status().isForbidden()); // 没有 admin.users：入口权限即拒绝
        JsonNode view = body(mvc.perform(get("/api/admin/ownership").session(owner.session())).andExpect(status().isOk()));
        assertEquals(owner.id(), view.path("owner").path("id").asLong());
        List<Long> ids = new ArrayList<>(); view.path("candidates").forEach(c -> ids.add(c.path("id").asLong()));
        assertEquals(List.of(admin1.id(), admin2.id()), ids, "ordinary members, disabled administrators and the owner are not candidates; list is not paged by the user list");
        assertEquals(1, view.path("version").asLong());
        // 没有候选人
        jdbc.update("update user_accounts set account_status='DISABLED' where id in (?,?)", admin1.id(), admin2.id());
        assertEquals(0, body(mvc.perform(get("/api/admin/ownership").session(owner.session())).andExpect(status().isOk())).path("candidates").size());
        jdbc.update("update user_accounts set account_status='ACTIVE' where id in (?,?)", admin1.id(), admin2.id());
    }

    @Test @Order(4) void invalidTransfersAreRejectedWithoutChangingEitherIdentity() throws Exception {
        long version = ownerVersion(), epochOwner = epochOf(owner.id()), epochTarget = epochOf(admin1.id());
        int rejectedBefore = count("select count(*) from access_audit_log where type='OWNER_TRANSFER' and outcome='REJECTED'");
        Object[][] cases = {
                {Map.of("targetId", admin1.id(), "expectedOwnerId", owner.id(), "expectedVersion", version + 5, "note", "交接", "confirmEmail", admin1.email()), 409},
                {Map.of("targetId", admin1.id(), "expectedOwnerId", admin2.id(), "expectedVersion", version, "note", "交接", "confirmEmail", admin1.email()), 409},
                {Map.of("targetId", owner.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "交接", "confirmEmail", owner.email()), 422},
                {Map.of("targetId", member.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "交接", "confirmEmail", member.email()), 422},
                {Map.of("targetId", disabledAdmin.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "交接", "confirmEmail", disabledAdmin.email()), 422},
                {Map.of("targetId", 99999999L, "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "交接", "confirmEmail", "x@y.z"), 422},
                {Map.of("targetId", admin1.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "   ", "confirmEmail", admin1.email()), 400},
                {Map.of("targetId", admin1.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "字".repeat(201), "confirmEmail", admin1.email()), 400},
                {Map.of("targetId", admin1.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "交接", "confirmEmail", admin2.email()), 400},
                {Map.of("targetId", admin1.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "交接"), 400},
        };
        for (Object[] c : cases) transfer(owner, (Map<String, Object>) c[0]).andExpect(status().is((Integer) c[1]));
        transfer(admin1, Map.of("targetId", admin2.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "越权", "confirmEmail", admin2.email())).andExpect(status().isForbidden());
        mvc.perform(t.write(post("/api/admin/ownership"), null, Map.of("targetId", admin1.id()))).andExpect(status().isUnauthorized());
        assertOneOwner(owner.id());
        assertEquals(version, ownerVersion());
        assertEquals(epochOwner, epochOf(owner.id())); assertEquals(epochTarget, epochOf(admin1.id()));
        assertEquals(rejectedBefore + cases.length + 1, count("select count(*) from access_audit_log where type='OWNER_TRANSFER' and outcome='REJECTED'"), "every rejection by an authorized operator is recorded (including the non-owner administrator)");
        // 邮箱核对不区分大小写、忽略首尾空白
        JsonNode ok = body(transfer(owner, Map.of("targetId", admin1.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "  交接大小写  ", "confirmEmail", "  " + admin1.email().toUpperCase() + " "))
                .andExpect(status().isOk()));
        assertEquals(admin1.id(), ok.path("owner").path("id").asLong());
        // 为后续测试还原：由新站长转回
        MockHttpSession fresh = new MockHttpSession();
        TestUsers.login(mvc, fresh, admin1.email(), "password123").andExpect(status().isOk()).andExpect(jsonPath("$.role.code").value("OWNER"));
        transferBack(new Account(admin1.id(), admin1.email(), admin1.name(), fresh), owner);
    }

    @Test @Order(5) void transferSwapsOwnershipAndTheSessionsFollowTheContract() throws Exception {
        MockHttpSession ownerOther = login(owner), successorOne = login(admin1), successorTwo = login(admin1), bystander = login(admin2);
        MockHttpSession ownerMain = owner.session();
        JsonNode profileBefore = body(mvc.perform(get("/api/auth/me").session(successorOne)).andExpect(status().isOk()));
        long version = ownerVersion();
        long roleVersion = rolesVersion(owner);
        MockHttpSession relogin = new MockHttpSession();
        JsonNode done = body(transfer(owner, Map.of("targetId", admin1.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "职责交接", "confirmEmail", admin1.email()), ownerMain).andExpect(status().isOk()));
        assertEquals("ADMIN", done.path("formerOwnerRole").path("code").asText());

        assertOneOwner(admin1.id());
        assertEquals("ADMIN", roleOf(owner.id()));
        assertEquals(version + 1, ownerVersion());
        assertTrue(rolesVersion(new Account(admin1.id(), admin1.email(), admin1.name(), login(admin1))) > roleVersion, "role page versions move so open editors reload");
        // 原站长当前会话保留，立即按管理员处理
        mvc.perform(get("/api/auth/me").session(ownerMain)).andExpect(status().isOk()).andExpect(jsonPath("$.role.code").value("ADMIN"));
        mvc.perform(get("/api/admin/users").session(ownerMain)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/ownership").session(ownerMain)).andExpect(status().isForbidden());
        disable(new Account(owner.id(), owner.email(), owner.name(), ownerMain), admin1.id()).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_OWNER_TARGET));
        // 双方其他旧会话失效；无关管理员不受影响
        mvc.perform(get("/api/auth/me").session(ownerOther)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(successorOne)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(successorTwo)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(bystander)).andExpect(status().isOk()).andExpect(jsonPath("$.role.code").value("ADMIN"));
        // 接任人重新登录后是站长，资料不变
        TestUsers.login(mvc, relogin, admin1.email(), "password123").andExpect(status().isOk()).andExpect(jsonPath("$.role.code").value("OWNER"))
                .andExpect(jsonPath("$.displayName").value(profileBefore.path("displayName").asText()));
        JsonNode view = body(mvc.perform(get("/api/admin/ownership").session(relogin)).andExpect(status().isOk()));
        List<Long> ids = new ArrayList<>(); view.path("candidates").forEach(c -> ids.add(c.path("id").asLong()));
        assertTrue(ids.contains(owner.id()) && ids.contains(admin2.id()), "the former owner is now a normal administrator candidate");
        // 记录
        JsonNode entry = body(mvc.perform(get("/api/admin/audit").param("type", "OWNER_TRANSFER").param("q", "职责交接").session(relogin)).andExpect(status().isOk())).path("items").get(0);
        assertEquals("SUCCESS", entry.path("outcome").asText()); assertEquals("TRANSFER", entry.path("action").asText());
        assertEquals(owner.name(), entry.path("operatorName").asText()); assertEquals(admin1.name(), entry.path("targetUserName").asText());
        assertEquals("站长", entry.path("afterRoleName").asText());
        // 后续转交由新站长发起：原站长不能自行撤销
        transfer(new Account(owner.id(), owner.email(), owner.name(), ownerMain), Map.of("targetId", owner.id(), "expectedOwnerId", admin1.id(), "expectedVersion", ownerVersion(), "note", "撤销", "confirmEmail", owner.email())).andExpect(status().isForbidden());
        assertOneOwner(admin1.id());
        // 由新站长转回，恢复到原始布局
        transferBack(new Account(admin1.id(), admin1.email(), admin1.name(), relogin), owner);
    }

    // —— 可靠性（UA-A15） ——

    @Test @Order(6) void transferFailureIsAtomicAndRecorded() throws Exception {
        long version = ownerVersion(), epochOwner = epochOf(owner.id()), epochTarget = epochOf(admin2.id());
        MockHttpSession ownerSession = owner.session();
        jdbc.execute("alter table access_control_state add constraint zz_block_owner check (owner_user_id is null or owner_user_id <> " + admin2.id() + ")");
        try {
            transfer(owner, Map.of("targetId", admin2.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "失败演练", "confirmEmail", admin2.email()), ownerSession)
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value(AccessAdminService.MSG_TRANSFER_UNAVAILABLE));
        } finally { jdbc.execute("alter table access_control_state drop constraint zz_block_owner"); }
        assertOneOwner(owner.id());
        assertEquals("ADMIN", roleOf(admin2.id()));
        assertEquals(version, ownerVersion());
        assertEquals(epochOwner, epochOf(owner.id())); assertEquals(epochTarget, epochOf(admin2.id()));
        mvc.perform(get("/api/auth/me").session(ownerSession)).andExpect(status().isOk()).andExpect(jsonPath("$.role.code").value("OWNER"));
        assertEquals("FAILED", jdbc.queryForObject("select outcome from access_audit_log where type='OWNER_TRANSFER' and note='失败演练'", String.class));
        // 重试成功后再转回
        transfer(owner, Map.of("targetId", admin2.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "重试", "confirmEmail", admin2.email()), ownerSession).andExpect(status().isOk());
        MockHttpSession fresh = new MockHttpSession();
        TestUsers.login(mvc, fresh, admin2.email(), "password123").andExpect(status().isOk());
        transferBack(new Account(admin2.id(), admin2.email(), admin2.name(), fresh), owner);
    }

    @Test @Order(7) void concurrentTransfersYieldExactlyOneOwner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 4; round++) {
                long version = ownerVersion();
                int successBefore = count("select count(*) from access_audit_log where type='OWNER_TRANSFER' and outcome='SUCCESS'");
                MockHttpSession ownerSession = owner.session();
                CountDownLatch go = new CountDownLatch(1);
                Account other = round % 2 == 0 ? admin1 : admin2;
                List<Future<Integer>> calls = new ArrayList<>();
                for (Account target : List.of(admin1, admin2, admin1, other)) calls.add(pool.submit(() -> {
                    go.await();
                    return transfer(owner, Map.of("targetId", target.id(), "expectedOwnerId", owner.id(), "expectedVersion", version, "note", "并发", "confirmEmail", target.email()), ownerSession).andReturn().getResponse().getStatus();
                }));
                // 同时：原站长停用某位管理员（转交后他就不再有此权限）
                Future<Integer> competing = pool.submit(() -> { go.await(); return disable(owner, admin2.id()).andReturn().getResponse().getStatus(); });
                go.countDown();
                int ok = 0;
                for (Future<Integer> f : calls) { int s = f.get(30, TimeUnit.SECONDS); if (s == 200) ok++; else assertTrue(s == 403 || s == 409 || s == 401 || s == 422, "unexpected " + s); }
                competing.get(30, TimeUnit.SECONDS);
                assertEquals(1, ok, "exactly one transfer wins");
                assertEquals(successBefore + 1, count("select count(*) from access_audit_log where type='OWNER_TRANSFER' and outcome='SUCCESS'"));
                long newOwner = jdbc.queryForObject("select owner_user_id from access_control_state where id=1", Long.class);
                assertOneOwner(newOwner);
                assertEquals(version + 1, ownerVersion());
                jdbc.update("update user_accounts set account_status='ACTIVE' where id=?", admin2.id()); // 竞争的停用可能已生效
                // 还原为原站长
                MockHttpSession fresh = new MockHttpSession();
                Account winner = newOwner == admin1.id() ? admin1 : admin2;
                TestUsers.login(mvc, fresh, winner.email(), "password123").andExpect(status().isOk());
                transferBack(new Account(winner.id(), winner.email(), winner.name(), fresh), owner);
            }
        } finally { pool.shutdownNow(); }
        assertTrue(count("select count(*) from user_accounts u join role_permissions rp on rp.role_id=u.role_id and rp.permission='admin.roles' where u.account_status='ACTIVE'") >= 1);
    }

    // —— 启动初始化与自愈（契约 §7） ——

    @Test @Order(8) void bootstrapPicksAnOwnerAndHealsStrayOwnerRoles() throws Exception {
        // 指针为空：选 id 最小的正常管理者（原站长）
        jdbc.update("update access_control_state set owner_user_id=null where id=1");
        jdbc.update("update user_accounts set role_id=(select id from roles where code='ADMIN') where id=?", owner.id());
        bootstrap.initialize();
        assertOneOwner(owner.id());
        assertEquals(1, count("select count(*) from access_audit_log where type='OWNER_TRANSFER' and action='INIT' and target_user_id=" + owner.id()));
        // 回退旧包期间被误分配的多余站长角色降为管理员，并留下系统记录
        jdbc.update("update user_accounts set role_id=(select id from roles where code='OWNER') where id=?", member.id());
        bootstrap.initialize();
        assertOneOwner(owner.id());
        assertEquals("ADMIN", roleOf(member.id()));
        assertEquals(1, count("select count(*) from access_audit_log where type='OWNER_TRANSFER' and action='INIT' and target_user_id=" + member.id()));
        bootstrap.initialize(); // 幂等
        assertEquals(2, count("select count(*) from access_audit_log where type='OWNER_TRANSFER' and action='INIT'"));
        jdbc.update("update user_accounts set role_id=(select id from roles where code='MEMBER') where id=?", member.id());
    }

    // —— 辅助 ——

    ResultActions disable(Account actor, long id) throws Exception { return change(actor, id, "DISABLED", "层级测试"); }
    ResultActions restore(Account actor, long id) throws Exception { return change(actor, id, "ACTIVE", "层级测试"); }
    ResultActions change(Account actor, long id, String status, String note) throws Exception {
        long version = jdbc.queryForObject("select status_version from user_accounts where id=?", Long.class, id);
        return mvc.perform(t.write(patch("/api/admin/users/" + id + "/status"), actor.session(), Map.of("status", status, "expectedVersion", version, "note", note)));
    }
    ResultActions assign(Account actor, long id, String roleCode) throws Exception {
        return mvc.perform(t.write(patch("/api/admin/users/" + id + "/role"), actor.session(), Map.of("roleCode", roleCode)));
    }
    ResultActions transfer(Account actor, Map<String, Object> body) throws Exception { return transfer(actor, body, actor.session()); }
    ResultActions transfer(Account actor, Map<String, Object> body, MockHttpSession session) throws Exception {
        return mvc.perform(t.write(post("/api/admin/ownership"), session, body));
    }
    /** 由当前站长（刚登录的会话）把站长转回 original，并让 original 的静态会话重新有效。 */
    void transferBack(Account currentOwner, Account original) throws Exception {
        transfer(currentOwner, Map.of("targetId", original.id(), "expectedOwnerId", currentOwner.id(), "expectedVersion", ownerVersion(), "note", "还原", "confirmEmail", original.email())).andExpect(status().isOk());
        assertOneOwner(original.id());
        MockHttpSession fresh = new MockHttpSession();
        TestUsers.login(mvc, fresh, original.email(), "password123").andExpect(status().isOk());
        owner = new Account(original.id(), original.email(), original.name(), fresh);
    }
    ResultActions roleCall(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Account actor, Object body) throws Exception {
        return mvc.perform(t.write(request, actor.session(), body));
    }
    ResultActions putPerms(Account actor, Map<String, List<String>> permissions) throws Exception {
        return mvc.perform(t.write(put("/api/admin/roles"), actor.session(), Map.of("version", rolesVersion(actor), "permissions", permissions)));
    }
    MockHttpSession login(Account a) throws Exception {
        MockHttpSession s = new MockHttpSession();
        TestUsers.login(mvc, s, a.email(), "password123").andExpect(status().isOk());
        return s;
    }
    JsonNode users(Account actor) throws Exception { return body(mvc.perform(get("/api/admin/users").param("size", "50").session(actor.session())).andExpect(status().isOk())); }
    JsonNode roles(Account actor) throws Exception { return body(mvc.perform(get("/api/admin/roles").session(actor.session())).andExpect(status().isOk())); }
    long rolesVersion(Account actor) throws Exception { return roles(actor).path("version").asLong(); }
    static JsonNode find(JsonNode page, long id) { for (JsonNode i : page.path("items")) if (i.path("id").asLong() == id) return i; throw new AssertionError("no user " + id); }
    static JsonNode roleItem(JsonNode view, String code) { for (JsonNode r : view.path("roles")) if (code.equals(r.path("code").asText())) return r; throw new AssertionError("no role " + code); }
    static List<String> perms(JsonNode view, String code) { List<String> l = new ArrayList<>(); roleItem(view, code).path("permissions").forEach(p -> l.add(p.asText())); return l; }
    JsonNode body(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    long ownerVersion() { return jdbc.queryForObject("select owner_version from access_control_state where id=1", Long.class); }
    long epochOf(long id) { return jdbc.queryForObject("select session_epoch from user_accounts where id=?", Long.class, id); }
    String roleOf(long id) { return jdbc.queryForObject("select r.code from user_accounts u join roles r on r.id=u.role_id where u.id=?", String.class, id); }
    int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    /** 唯一站长不变量：指针 = 唯一持有站长角色的用户。 */
    void assertOneOwner(long expected) {
        assertEquals(expected, jdbc.queryForObject("select owner_user_id from access_control_state where id=1", Long.class));
        List<Long> holders = jdbc.queryForList("select u.id from user_accounts u join roles r on r.id=u.role_id where r.system_kind='OWNER'", Long.class);
        assertEquals(List.of(expected), holders, "exactly one user holds the owner role");
    }
}
