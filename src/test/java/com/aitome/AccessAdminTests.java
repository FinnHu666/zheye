package com.aitome;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** FINNS-11 验收 A03/A04/A05/A09/A10：管理接口与保护规则（契约 §5）。独立数据库，便于控制管理员与角色数量。 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:aitome-access-admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        // 独立 H2 库：即使在 postgres 配置下运行，也不执行 PostgreSQL 专用的 Flyway 脚本
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccessAdminTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    AccessTestSupport t;
    static AccessTestSupport.Account admin;

    @BeforeEach void setUp() throws Exception {
        t = new AccessTestSupport(mvc, json, jdbc);
        if (admin == null) admin = t.withRole("admin", "ADMIN");
    }

    @Test @Order(1) void onlyAdminIsManagerAndCannotBeDemoted() throws Exception {
        JsonNode users = body(mvc.perform(get("/api/admin/users").session(admin.session())).andExpect(status().isOk()));
        assertEquals(1, users.path("managerCount").asLong());
        call(patch("/api/admin/users/" + admin.id() + "/role"), Map.of("roleCode", "MEMBER", "expectedRoleCode", "ADMIN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("最后一位能管理角色")));
    }

    @Test @Order(2) void usersCanBeSearchedFilteredPagedAndAssigned() throws Exception {
        List<AccessTestSupport.Account> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) created.add(t.register("pager"));
        JsonNode page0 = body(mvc.perform(get("/api/admin/users").param("q", "PAGER").param("size", "5").session(admin.session())).andExpect(status().isOk()));
        assertEquals(12, page0.path("total").asLong());
        assertEquals(5, page0.path("items").size());
        JsonNode page2 = body(mvc.perform(get("/api/admin/users").param("q", "pager").param("size", "5").param("page", "2").session(admin.session())));
        assertEquals(2, page2.path("items").size());
        assertEquals(0, body(mvc.perform(get("/api/admin/users").param("q", "%").session(admin.session()))).path("total").asLong(), "LIKE wildcards are escaped");

        long target = created.get(0).id();
        JsonNode assigned = body(call(patch("/api/admin/users/" + target + "/role"), Map.of("roleCode", "TRUSTED", "expectedRoleCode", "MEMBER", "note", "负责运维")).andExpect(status().isOk()));
        assertEquals("TRUSTED", assigned.path("role").path("code").asText());
        call(patch("/api/admin/users/" + target + "/role"), Map.of("roleCode", "RESTRICTED", "expectedRoleCode", "MEMBER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("该用户角色已被修改为「受信成员」，请核对后重试"));
        call(patch("/api/admin/users/" + target + "/role"), Map.of("roleCode", "NOPE")).andExpect(status().isBadRequest());
        call(patch("/api/admin/users/999999/role"), Map.of("roleCode", "MEMBER")).andExpect(status().isNotFound());
        JsonNode filtered = body(mvc.perform(get("/api/admin/users").param("role", "TRUSTED").session(admin.session())));
        assertTrue(filtered.path("total").asLong() >= 1);
        filtered.path("items").forEach(u -> assertEquals("TRUSTED", u.path("role").path("code").asText()));
        mvc.perform(get("/api/auth/me").session(created.get(0).session())).andExpect(jsonPath("$.role.code").value("TRUSTED"));
    }

    @Test @Order(3) void permissionChangesApplyImmediatelyAndRespectLocksAndVersion() throws Exception {
        var restricted = t.withRole("perm", "RESTRICTED");
        mvc.perform(t.write(post("/api/posts"), restricted.session(), Map.of("type", "TOPIC", "title", "t", "summary", "s", "body", "b"))).andExpect(status().isForbidden());
        JsonNode roles = roles();
        long version = roles.path("version").asLong();
        List<String> restrictedPerms = new ArrayList<>(permissionsOf(roles, "RESTRICTED"));
        restrictedPerms.add("post.create");
        JsonNode saved = body(call(put("/api/admin/roles"), Map.of("version", version, "permissions", Map.of("RESTRICTED", restrictedPerms), "note", "允许发布")).andExpect(status().isOk()));
        assertEquals(version + 1, saved.path("version").asLong());
        mvc.perform(t.write(post("/api/posts"), restricted.session(), Map.of("type", "TOPIC", "title", "t", "summary", "s", "body", "b"))).andExpect(status().isCreated());

        call(put("/api/admin/roles"), Map.of("version", version, "permissions", Map.of("RESTRICTED", restrictedPerms))).andExpect(status().isConflict());
        long v2 = saved.path("version").asLong();
        call(put("/api/admin/roles"), Map.of("version", v2, "permissions", Map.of("MEMBER", List.of("content.public")))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("个人资料与我的内容")));
        List<String> adminWithout = new ArrayList<>(permissionsOf(roles, "ADMIN")); adminWithout.remove("admin.roles");
        call(put("/api/admin/roles"), Map.of("version", v2, "permissions", Map.of("ADMIN", adminWithout))).andExpect(status().isUnprocessableEntity());
        call(put("/api/admin/roles"), Map.of("version", v2, "permissions", Map.of("MEMBER", List.of("content.public", "profile.manage", "bogus")))).andExpect(status().isBadRequest());
        // 同一版本的锁定校验失败不应改变任何数据
        assertEquals(v2, roles().path("version").asLong());
        // 撤回，保持后续测试的种子权限
        restrictedPerms.remove("post.create");
        call(put("/api/admin/roles"), Map.of("version", v2, "permissions", Map.of("RESTRICTED", restrictedPerms))).andExpect(status().isOk());
    }

    @Test @Order(4) void rolesCanBeCreatedRenamedAndDeletedWithMemberTransfer() throws Exception {
        JsonNode created = body(call(post("/api/admin/roles/definitions"), Map.of("version", version(), "name", "内容审核员", "description", "协助处理违规内容", "copyFrom", "TRUSTED"))
                .andExpect(status().isCreated()));
        String code = created.path("createdCode").asText();
        assertTrue(code.matches("R\\d+"), code);
        assertEquals(permissionsOf(created, "TRUSTED"), permissionsOf(created, code));
        call(post("/api/admin/roles/definitions"), Map.of("version", version(), "name", "内容审核员")).andExpect(status().isConflict());
        call(post("/api/admin/roles/definitions"), Map.of("version", version(), "name", "一二三四五六七八九十一二三四五六七八九十一")).andExpect(status().isBadRequest());
        call(post("/api/admin/roles/definitions"), Map.of("version", version(), "name", "  ")).andExpect(status().isBadRequest());
        JsonNode basic = body(call(post("/api/admin/roles/definitions"), Map.of("version", version(), "name", "访客体验")).andExpect(status().isCreated()));
        assertEquals(List.of("content.public", "profile.manage"), permissionsOf(basic, basic.path("createdCode").asText()));

        var reviewer = t.register("reviewer");
        call(patch("/api/admin/users/" + reviewer.id() + "/role"), Map.of("roleCode", code)).andExpect(status().isOk());
        call(patch("/api/admin/roles/definitions/" + code), Map.of("version", version(), "name", "审核员", "description", "改名")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(reviewer.session())).andExpect(jsonPath("$.role.name").value("审核员"));
        call(patch("/api/admin/roles/definitions/" + code), Map.of("version", version(), "name", "普通成员")).andExpect(status().isConflict());

        call(delete("/api/admin/roles/definitions/ADMIN"), Map.of("version", version())).andExpect(status().isUnprocessableEntity());
        call(delete("/api/admin/roles/definitions/MEMBER"), Map.of("version", version())).andExpect(status().isUnprocessableEntity());
        call(delete("/api/admin/roles/definitions/" + code), Map.of("version", version())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该角色下还有 1 位用户，请选择他们改用的角色"));
        call(delete("/api/admin/roles/definitions/" + code), Map.of("version", version(), "moveTo", "MEMBER", "note", "合并角色")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(reviewer.session())).andExpect(jsonPath("$.role.code").value("MEMBER"));
        call(patch("/api/admin/users/" + reviewer.id() + "/role"), Map.of("roleCode", code)).andExpect(status().isBadRequest());
        call(delete("/api/admin/roles/definitions/" + basic.path("createdCode").asText()), Map.of("version", version())).andExpect(status().isOk());
    }

    @Test @Order(5) void roleCountIsCappedAtTwelve() throws Exception {
        List<String> codes = new ArrayList<>();
        int existing = roles().path("roles").size();
        for (int i = existing; i < 12; i++)
            codes.add(body(call(post("/api/admin/roles/definitions"), Map.of("version", version(), "name", "临时" + i)).andExpect(status().isCreated())).path("createdCode").asText());
        call(post("/api/admin/roles/definitions"), Map.of("version", version(), "name", "超出")).andExpect(status().isUnprocessableEntity());
        for (String code : codes) call(delete("/api/admin/roles/definitions/" + code), Map.of("version", version())).andExpect(status().isOk());
    }

    @Test @Order(6) void auditKeepsNameSnapshotsAndSupportsFilters() throws Exception {
        JsonNode all = audit(Map.of("size", "50"));
        assertTrue(all.path("total").asLong() >= 8);
        JsonNode deleted = audit(Map.of("type", "ROLE_DEFINE", "q", "审核员", "size", "50"));
        assertTrue(deleted.path("items").findValuesAsText("action").containsAll(List.of("CREATE", "UPDATE", "DELETE")));
        JsonNode delete = audit(Map.of("q", "合并角色")).path("items").get(0);
        assertEquals("审核员", delete.path("roleName").asText(), "deleted role keeps its last name");
        assertEquals("普通成员", delete.path("moveToName").asText());
        assertEquals(1, delete.path("movedCount").asInt());

        JsonNode permission = audit(Map.of("type", "ROLE_PERMISSION", "q", "发布文章"));
        assertTrue(permission.path("total").asLong() >= 2);
        assertEquals("受限成员", permission.path("items").get(0).path("roleName").asText());
        JsonNode byRole = audit(Map.of("role", "TRUSTED", "size", "50"));
        assertTrue(byRole.path("total").asLong() >= 1);
        JsonNode note = audit(Map.of("q", "负责运维"));
        assertEquals("ROLE_ASSIGN", note.path("items").get(0).path("type").asText());
        assertEquals("受信成员", note.path("items").get(0).path("afterRoleName").asText());

        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        assertEquals(all.path("total").asLong(), audit(Map.of("from", today, "to", today, "size", "50")).path("total").asLong());
        assertEquals(0, audit(Map.of("to", LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(2).toString())).path("total").asLong());
        mvc.perform(get("/api/admin/audit").param("from", today).param("to", "2000-01-01").session(admin.session()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("开始日期不能晚于结束日期"));
        JsonNode paged = audit(Map.of("size", "3", "page", "1"));
        assertEquals(3, paged.path("items").size());
        assertNotEquals(audit(Map.of("size", "3")).path("items").get(0).path("id").asLong(), paged.path("items").get(0).path("id").asLong());
    }

    @Test @Order(7) void writesRequireCsrfAndAdminPermission() throws Exception {
        mvc.perform(put("/api/admin/roles").session(admin.session()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"permissions\":{}}"))
                .andExpect(status().isForbidden());
        var member = t.register("plain");
        mvc.perform(t.write(patch("/api/admin/users/" + member.id() + "/role"), member.session(), Map.of("roleCode", "ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(member.session())).andExpect(jsonPath("$.role.code").value("MEMBER"));
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(t.write(request, admin.session(), body));
    }
    private JsonNode body(ResultActions actions) throws Exception { return json.readTree(actions.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    private JsonNode roles() throws Exception { return body(mvc.perform(get("/api/admin/roles").session(admin.session())).andExpect(status().isOk())); }
    private long version() throws Exception { return roles().path("version").asLong(); }
    private List<String> permissionsOf(JsonNode roles, String code) {
        for (JsonNode role : roles.path("roles")) if (code.equals(role.path("code").asText())) {
            List<String> result = new ArrayList<>(); role.path("permissions").forEach(p -> result.add(p.asText())); return result;
        }
        throw new AssertionError("role not found: " + code);
    }
    private JsonNode audit(Map<String, String> params) throws Exception {
        var request = get("/api/admin/audit").session(admin.session());
        params.forEach(request::param);
        return body(mvc.perform(request).andExpect(status().isOk()));
    }
}
