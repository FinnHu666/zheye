package com.aitome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约 CONTRACT_CONTENT_IMAGES §6：T-S1 全站 Referrer-Policy；T-S2 检测输入覆盖 Markdown 图片的说明与地址（D1）。H2 内存库。
 */
@SpringBootTest @AutoConfigureMockMvc
class ArticleImageTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    AccessTestSupport t;
    AccessTestSupport.Account author;

    @BeforeEach void setUp() throws Exception {
        TestReviewConfig.reset(jdbc);
        t = new AccessTestSupport(mvc, json, jdbc);
        author = t.register("imgauthor");
        jdbc.update("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,level,tag,version,created_at,updated_at) values(?,?,true,false,?,?,null,1,current_timestamp,current_timestamp)", "midword", "", "midword", "MEDIUM");
    }

    @AfterEach void tearDown() {
        jdbc.update("delete from article_drafts where author_id=?", author.id());
        jdbc.update("delete from content_posts where author_id=?", author.id());
        TestReviewConfig.reset(jdbc);
    }

    JsonNode body(ResultActions r) throws Exception {
        String text = r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }
    String publishBody(String text) throws Exception {
        long id = body(mvc.perform(t.write(post("/api/me/articles"), author.session(), Map.of("title", "图片文章", "summary", "摘要", "body", text))).andExpect(status().isCreated())).path("id").asLong();
        return body(mvc.perform(t.write(post("/api/me/articles/" + id + "/publish"), author.session(),
                Map.of("title", "图片文章", "summary", "摘要", "body", text, "expectedVersion", 1, "requestId", UUID.randomUUID().toString()))).andExpect(status().isOk())).path("outcome").asText();
    }

    @Test void everyResponseCarriesNoReferrer() throws Exception {
        mvc.perform(get("/")).andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/me/posts/1/edit")).andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/auth/reset")).andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/api/stats")).andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test void imageAltTextIsDetected() throws Exception {
        assertEquals("PENDING", publishBody("正文\n\n![midword](https://img.example/a.png)"));
    }

    @Test void imageAddressIsDetected() throws Exception {
        assertEquals("PENDING", publishBody("正文\n\n![图](https://img.example/midword.png)"));
    }

    @Test void cleanImagePublishes() throws Exception {
        assertEquals("PUBLISHED", publishBody("正文\n\n![风景](https://img.example/a.png)"));
    }
}
