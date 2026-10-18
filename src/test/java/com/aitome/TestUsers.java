package com.aitome;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 测试夹具：FINNS-15 之后注册必须经过邮件验证，其他功能的测试不需要重复走一遍邮件流程，
 * 因此这里直接创建“已验证”的账号（与验证流程创建的账号一致），再通过真实的登录接口取得会话。
 */
final class TestUsers {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    private TestUsers() {}

    static void create(JdbcTemplate jdbc, String email, String password, String displayName) {
        jdbc.update("insert into user_accounts(email,password_hash,display_name,bio,created_at,role_id,email_verified_at) values(?,?,?,?,?,(select id from roles where system_kind='DEFAULT'),?)",
                email.toLowerCase(), ENCODER.encode(password), displayName, "", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    /** 创建已验证账号并用真实登录接口把会话登录；返回登录结果供断言。 */
    static ResultActions registerAndLogin(MockMvc mvc, JdbcTemplate jdbc, MockHttpSession session, String email, String password, String displayName) throws Exception {
        create(jdbc, email, password, displayName);
        return login(mvc, session, email, password);
    }

    static ResultActions login(MockMvc mvc, MockHttpSession session, String email, String password) throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        return mvc.perform(post("/api/auth/login").session(session).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
