package com.aitome;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 角色与权限测试的公共工具：注册账户、直接设置角色、带 CSRF 发送写请求。 */
final class AccessTestSupport {
    private final MockMvc mvc; private final ObjectMapper json; private final JdbcTemplate jdbc;

    AccessTestSupport(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc) { this.mvc = mvc; this.json = json; this.jdbc = jdbc; }

    record Account(long id, String email, String name, MockHttpSession session) {}

    Account register(String prefix) throws Exception {
        return register(prefix, Map.of());
    }

    /** 已验证账号 + 真实登录（FINNS-15 之后注册须经邮件验证，见 TestUsers）。extra 中的任何字段都不会影响角色或身份。 */
    Account register(String prefix, Map<String, Object> extra) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@finns.test";
        String name = (prefix + UUID.randomUUID().toString().substring(0, 4)).substring(0, Math.min(prefix.length() + 4, 40));
        TestUsers.create(jdbc, email, "password123", name);
        String response = TestUsers.login(mvc, session, email, "password123").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Account(json.readTree(response).path("id").asLong(), email, name, session);
    }

    Account withRole(String prefix, String roleCode) throws Exception {
        Account account = register(prefix);
        setRole(account.id(), roleCode);
        return account;
    }

    void setRole(long userId, String roleCode) {
        int updated = jdbc.update("update user_accounts set role_id=(select id from roles where code=?) where id=?", roleCode, userId);
        if (updated != 1) throw new IllegalStateException("cannot set role " + roleCode);
        // 站长只能经转交改变（FINNS-16）；测试直接建立站长时同步站长指针，并让其他站长角色持有者降为管理员
        if ("OWNER".equals(roleCode)) {
            jdbc.update("update user_accounts set role_id=(select id from roles where code='ADMIN') where role_id=(select id from roles where code='OWNER') and id<>?", userId);
            jdbc.update("update access_control_state set owner_user_id=? where id=1", userId);
        }
    }

    Cookie csrf() throws Exception {
        Cookie cookie = mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        if (cookie == null) throw new AssertionError("CSRF cookie was not initialized");
        return cookie;
    }

    /** 为写请求附加会话与 CSRF。session 为 null 表示游客。 */
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, MockHttpSession session, Object body) throws Exception {
        Cookie csrf = csrf();
        request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : (body instanceof String s ? s : json.writeValueAsString(body)));
        if (session != null) request.session(session);
        return request;
    }

    MockHttpServletRequestBuilder read(MockHttpServletRequestBuilder request, MockHttpSession session) {
        if (session != null) request.session(session);
        return request;
    }
}
