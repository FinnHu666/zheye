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

/** FINNS-16 站长初始化：部署配置的站长邮箱优先，即使已有其他管理员、且该账号尚不是管理员（生产迁移场景）。独立上下文与数据库。 */
@SpringBootTest(properties = {
        "finns.access.bootstrap-admin-email=Prod.Owner@Finns.Test",
        "spring.datasource.url=jdbc:h2:mem:aitome-bootstrap-owner-email;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@DirtiesContext
class AccessBootstrapOwnerEmailTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired AccessBootstrap bootstrap;

    @Test void configuredOwnerEmailWinsEvenWhenAnotherManagerExistsAndItIsNotYetAnAdministrator() throws Exception {
        AccessTestSupport t = new AccessTestSupport(mvc, json, jdbc);
        TestUsers.create(jdbc, "other.admin@finns.test", "password123", "旧管理员");
        long other = jdbc.queryForObject("select id from user_accounts where email='other.admin@finns.test'", Long.class);
        t.setRole(other, "ADMIN"); // 迁移前的库里已经有一位管理员
        TestUsers.create(jdbc, "prod.owner@finns.test", "password123", "生产站长");
        long owner = jdbc.queryForObject("select id from user_accounts where email='prod.owner@finns.test'", Long.class); // 普通成员
        bootstrap.initialize();
        assertEquals("OWNER", roleOf(owner), "the deployment-configured owner email is chosen although another administrator exists");
        assertEquals(owner, jdbc.queryForObject("select owner_user_id from access_control_state where id=1", Long.class));
        assertEquals("ADMIN", roleOf(other), "the existing administrator keeps the administrator role");
        bootstrap.initialize(); // 幂等
        assertEquals(1, jdbc.queryForObject("select count(*) from user_accounts u join roles r on r.id=u.role_id where r.system_kind='OWNER'", Integer.class));
    }

    private String roleOf(long userId) { return jdbc.queryForObject("select r.code from user_accounts u join roles r on r.id=u.role_id where u.id=?", String.class, userId); }
}
