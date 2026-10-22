package com.aitome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FINNS-18 B4：旧测试用 POST /api/posts 造数据；该接口已停用，改用“创建草稿 → 发布”，返回公开文章 ID。 */
final class TestArticles {
    private TestArticles() {}

    /** 创建草稿并发布（须有启用的敏感规则且正文不命中，测试库启动时已放入中性规则）；断言结果为已发布，返回公开文章 ID。 */
    static long publish(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc, MockHttpSession session, String title, String summary, String body) throws Exception {
        AccessTestSupport t = new AccessTestSupport(mvc, json, jdbc);
        JsonNode draft = read(json, mvc.perform(t.write(post("/api/me/articles"), session, Map.of("title", title, "summary", summary, "body", body))).andExpect(status().isCreated()));
        long id = draft.path("id").asLong();
        JsonNode result = read(json, mvc.perform(t.write(post("/api/me/articles/" + id + "/publish"), session,
                Map.of("title", title, "summary", summary, "body", body, "expectedVersion", draft.path("version").asLong(), "requestId", UUID.randomUUID().toString()))).andExpect(status().isOk()));
        assertEquals("PUBLISHED", result.path("outcome").asText(), "测试造数据应直接发布: " + result);
        return result.path("article").path("postId").asLong();
    }

    private static JsonNode read(ObjectMapper json, org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
