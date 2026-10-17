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

    Account register(String prefix, Map<String, Object> extra) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@finns.test";
        String name = (prefix + UUID.randomUUID().toString().substring(0, 4)).substring(0, Math.min(prefix.length() + 4, 40));
        var body = new java.util.HashMap<String, Object>(Map.of("email", email, "password", "password123", "displayName", name));
        body.putAll(extra);
        Cookie csrf = csrf();
        String response = mvc.perform(post("/api/auth/register").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
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
