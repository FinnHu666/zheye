package com.aitome;

import com.aitome.access.AccessBootstrap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 首位管理员按部署配置指定且幂等（契约 §4，验收 A05 初始化部分）。独立上下文与数据库。 */
@SpringBootTest(properties = {
        "finns.access.bootstrap-admin-email=Founder@Finns.Test",
        "spring.datasource.url=jdbc:h2:mem:aitome-bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        // 独立 H2 库：即使在 postgres 配置下运行，也不执行 PostgreSQL 专用的 Flyway 脚本
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@DirtiesContext
class AccessBootstrapTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired AccessBootstrap bootstrap;

    @Test void configuredEmailBecomesFirstAdminOnceAndOnlyWhenNoManagerExists() throws Exception {
        AccessTestSupport t = new AccessTestSupport(mvc, json, jdbc);
        assertEquals(0, managers(), "fresh database starts without managers");
        long founder = registerFounder(t);
        assertEquals("MEMBER", roleOf(founder));

        bootstrap.initialize();
        assertEquals("ADMIN", roleOf(founder));
        assertEquals(1, jdbc.queryForObject("select count(*) from access_audit_log where type='ROLE_ASSIGN' and target_user_id=? and operator_name='系统初始化'", Integer.class, founder));

        bootstrap.initialize(); // 已有管理者：不重复写入
        assertEquals(1, jdbc.queryForObject("select count(*) from access_audit_log where operator_name='系统初始化'", Integer.class));
        assertEquals(4, jdbc.queryForObject("select count(*) from roles", Integer.class), "seed roles are not duplicated");
    }

    private long registerFounder(AccessTestSupport t) throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var csrf = t.csrf();
        String body = json.writeValueAsString(java.util.Map.of("email", "founder@finns.test", "password", "password123", "displayName", "站长"));
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/register").session(session)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(response).path("id").asLong();
    }

    private int managers() {
        return jdbc.queryForObject("select count(*) from user_accounts u join role_permissions rp on rp.role_id=u.role_id where rp.permission='admin.roles'", Integer.class);
    }

    private String roleOf(long userId) {
        return jdbc.queryForObject("select r.code from user_accounts u join roles r on r.id=u.role_id where u.id=?", String.class, userId);
    }
}
