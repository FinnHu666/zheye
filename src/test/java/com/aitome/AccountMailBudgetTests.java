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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 全局日发送预算（概设 §3.3.1）：用尽后新的令牌邮件按发送失败处理，公开响应不变；通知邮件仍放行；次日恢复。 */
@SpringBootTest(properties = "finns.mail.daily-budget=3")
@AutoConfigureMockMvc
class AccountMailBudgetTests {
    static final String PW = "Passw0rd-12345";
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired TestAccountConfig.MutableClock clock; @Autowired TestAccountConfig.RecordingMailSender mail;

    // 预算按数据库里当天已发出的邮件计，持久的测试库（PostgreSQL）里可能留有之前运行的记录，先清空令牌表
    @BeforeEach void reset() { clock.reset(); mail.reset(); jdbc.update("delete from account_action_tokens"); }

    String email(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@finns.test"; }

    ResultActions send(String path, MockHttpSession session, Object body) throws Exception {
        Cookie csrf = mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var request = post(path).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (session != null) request.session(session);
        String ip = "budget-" + UUID.randomUUID();
        request.with(r -> { r.setRemoteAddr(ip); return r; });
        return mvc.perform(request);
    }

    JsonNode requestReset(String email) throws Exception {
        return json.readTree(send("/api/auth/request-link", null, Map.of("email", email, "purpose", "reset")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test void exhaustedBudgetTurnsNewLinkMailIntoASilentFailureButKeepsNoticesAndRecoversTomorrow() throws Exception {
        String[] users = {email("a"), email("b"), email("c"), email("d")};
        for (String u : users) TestUsers.create(jdbc, u, PW, "预算");
        JsonNode first = requestReset(users[0]);
        requestReset(users[1]); requestReset(users[2]);
        assertEquals(3, mail.sent.size(), "three mails fit the budget");

        JsonNode blocked = requestReset(users[3]);
        assertEquals(first, blocked, "the public response does not reveal that the budget is exhausted");
        assertEquals(0, mail.to(users[3]).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from account_action_tokens where email=?", Integer.class, users[3]), "no token row is created once the budget is exhausted");

        // 已有的有效链接不受影响
        String token = mail.lastToken(users[0]);
        send("/api/auth/link/inspect", null, Map.of("token", token, "purpose", "reset")).andExpect(status().isOk());

        // 通知邮件不被预算拒绝（改密关系账号安全）
        MockHttpSession session = new MockHttpSession();
        send("/api/auth/login", session, Map.of("email", users[1], "password", PW)).andExpect(status().isOk());
        send("/api/account/password", session, Map.of("oldPassword", PW, "password", "N3w-passw0rd!", "confirmPassword", "N3w-passw0rd!")).andExpect(status().isOk());
        assertTrue(mail.to(users[1]).stream().anyMatch(m -> m.subject().contains("已更新")));

        // 次日恢复（预算按审计时区的自然日计）
        clock.advance(Duration.ofHours(25));
        requestReset(users[3]);
        assertEquals(1, mail.to(users[3]).size());
    }
}
