package com.aitome;

import com.aitome.access.PublicEndpoint;
import com.aitome.access.RequirePermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** FINNS-11 验收 A01/A02/A08 及即时生效（契约 §4、§8）。 */
@SpringBootTest @AutoConfigureMockMvc
class AccessEnforcementTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    AccessTestSupport t;

    @BeforeEach void setUp() { t = new AccessTestSupport(mvc, json, jdbc); }

    @Test void everyApiEndpointDeclaresItsPermissionOrIsExplicitlyPublic() {
        List<String> missing = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            boolean api = info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/"));
            if (!api) return;
            if (!declared(handler)) missing.add(info.toString());
        });
        assertTrue(missing.isEmpty(), "endpoints without @RequirePermission/@PublicEndpoint: " + missing);
    }

    @Test void newAccountsAlwaysGetTheDefaultRole() throws Exception {
        var account = t.register("reg", Map.of("role", "ADMIN", "roleCode", "ADMIN"));
        JsonNode me = json.readTree(mvc.perform(get("/api/auth/me").session(account.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("MEMBER", me.path("role").path("code").asText());
        assertEquals("普通成员", me.path("role").path("name").asText());
        List<String> permissions = new ArrayList<>();
        me.path("permissions").forEach(p -> permissions.add(p.asText()));
        assertEquals(List.of("content.public", "profile.manage", "selection.manage", "post.create", "post.interact", "collection.manage", "ai.use"), permissions);
    }

    @Test void guestIsAskedToSignInForProtectedEndpointsButCanReadPublicContent() throws Exception {
        mvc.perform(get("/api/posts")).andExpect(status().isOk());
        mvc.perform(get("/api/collections")).andExpect(status().isOk());
        mvc.perform(get("/api/stats")).andExpect(status().isOk());
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("请先登录"));
        mvc.perform(get("/api/me/ai-settings/deepseek")).andExpect(status().isUnauthorized());
        long postId = anyPostId();
        mvc.perform(t.write(post("/api/posts/" + postId + "/like"), null, null)).andExpect(status().isUnauthorized());
    }

    @Test void restrictedMemberKeepsProfileAndSelectionButCannotPublishInteractOrUseAi() throws Exception {
        var user = t.withRole("restricted", "RESTRICTED");
        MockHttpSession s = user.session();
        mvc.perform(get("/api/me/profile").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/me/posts").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/me/collections/selected").session(s)).andExpect(status().isOk());
        mvc.perform(t.write(post("/api/me/articles"), s, Map.of("title", "t", "summary", "s", "body", "b")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("当前角色没有此操作权限"));
        mvc.perform(t.write(post("/api/posts/" + anyPostId() + "/comments"), s, Map.of("body", "hi"))).andExpect(status().isForbidden());
        mvc.perform(t.write(post("/api/posts/" + anyPostId() + "/like"), s, null)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/collections").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/ai-settings/deepseek").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/topic-builder/capabilities").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").session(s)).andExpect(status().isForbidden());
    }

    @Test void memberKeepsAllPreviousAbilitiesButNotAdministration() throws Exception {
        var user = t.withRole("member", "MEMBER");
        MockHttpSession s = user.session();
        mvc.perform(t.write(post("/api/me/articles"), s, Map.of("title", "成员发帖", "summary", "s", "body", "b"))).andExpect(status().isCreated());
        mvc.perform(t.write(post("/api/posts/" + anyPostId() + "/like"), s, null)).andExpect(status().isOk());
        mvc.perform(get("/api/me/collections").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/me/ai-settings/deepseek").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/roles").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit").session(s)).andExpect(status().isForbidden());
    }

    @Test void adminCanReachAdministrationButNotOtherUsersPrivateDrafts() throws Exception {
        var owner = t.withRole("owner", "MEMBER");
        String draft = mvc.perform(t.write(post("/api/me/collections/drafts"), owner.session(), Map.of("name", "私人草稿", "format", "CUSTOM")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long draftId = json.readTree(draft).path("id").asLong();
        var admin = t.withRole("admin", "ADMIN");
        mvc.perform(get("/api/admin/users").session(admin.session())).andExpect(status().isOk());
        mvc.perform(get("/api/me/collections/" + draftId + "/draft").session(admin.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/collections/" + draftId + "/preview").session(admin.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/private/tools").session(admin.session())).andExpect(result -> assertNotEquals(200, result.getResponse().getStatus(), "local tools stay behind the private key"));
    }

    @Test void roleChangeAppliesToTheNextRequestWithoutSigningOut() throws Exception {
        var user = t.withRole("live", "MEMBER");
        mvc.perform(get("/api/me/collections").session(user.session())).andExpect(status().isOk());
        t.setRole(user.id(), "RESTRICTED");
        mvc.perform(get("/api/me/collections").session(user.session())).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(user.session())).andExpect(jsonPath("$.role.code").value("RESTRICTED"));
        String roleId = String.valueOf(jdbc.queryForObject("select id from roles where code='RESTRICTED'", Long.class));
        jdbc.update("insert into role_permissions(role_id,permission) values(?, 'collection.manage')", Long.valueOf(roleId));
        try {
            mvc.perform(get("/api/me/collections").session(user.session())).andExpect(status().isOk());
        } finally {
            jdbc.update("delete from role_permissions where role_id=? and permission='collection.manage'", Long.valueOf(roleId));
        }
    }

    @Test void userWithoutRoleFromRollbackWindowBehavesAsDefaultRoleAndStaysListed() throws Exception {
        var user = t.register("rollback");
        jdbc.update("update user_accounts set role_id=null where id=?", user.id()); // 回退到 0.6.0 期间注册的用户没有角色
        mvc.perform(get("/api/auth/me").session(user.session())).andExpect(jsonPath("$.role.code").value("MEMBER"));
        mvc.perform(get("/api/me/collections").session(user.session())).andExpect(status().isOk());
        var admin = t.withRole("lister", "ADMIN");
        mvc.perform(get("/api/admin/users").param("q", user.email()).session(admin.session()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].role.code").value("MEMBER"));
    }

    @Test void staleSessionIsReportedAsExpired() throws Exception {
        MockHttpSession stale = new MockHttpSession();
        stale.setAttribute("USER_ID", 987654321L);
        mvc.perform(get("/api/me/profile").session(stale)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("登录已失效"));
        mvc.perform(get("/api/collections").session(stale)).andExpect(status().isOk()); // 公开内容按游客处理，不报 500
    }

    private boolean declared(HandlerMethod handler) {
        return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), RequirePermission.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PublicEndpoint.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RequirePermission.class)
                || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PublicEndpoint.class);
    }

    /** postgres 配置下没有演示数据，必要时由普通成员发布一篇。 */
    private long anyPostId() throws Exception {
        Long id = jdbc.queryForObject("select min(id) from content_posts", Long.class);
        if (id != null) return id;
        var author = t.withRole("seed-author", "MEMBER");
        return TestArticles.publish(mvc, json, jdbc, author.session(), "种子内容", "s", "b");
    }
}
