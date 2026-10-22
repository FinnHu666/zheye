package com.aitome;

import com.aitome.content.CollectionPublicationService;
import com.aitome.content.CollectionWorkspaceController.PublicEntry;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FINNS-18 B4（契约修订2 §3.10、§14.2）：POST /api/posts 停用并关闭话题创建；作者列表/资料计数只含可见内容；
 * 专题收录只接受可见文章，快照捕获对不可见文章只留占位。H2 内存库；CT-A12（部分）、CT-A16 阶段1（创建关闭）。
 * 阶段2（删除既有话题）与专题编辑期校验属 B8/FINNS-19，不在此。
 */
@SpringBootTest @AutoConfigureMockMvc
class ArticleVisibilityTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired CollectionPublicationService publications;
    AccessTestSupport t;
    AccessTestSupport.Account owner, author, restricted;

    @BeforeEach void setUp() throws Exception {
        TestReviewConfig.reset(jdbc);
        t = new AccessTestSupport(mvc, json, jdbc);
        owner = t.register("vsowner"); t.setRole(owner.id(), "OWNER");
        author = t.register("vsauthor");
        restricted = t.withRole("vsres", "RESTRICTED");
    }

    @AfterEach void tearDown() {
        for (long id : new long[]{owner.id(), author.id(), restricted.id()}) {
            jdbc.update("delete from collection_entries where collection_id in (select id from community_collections where owner_id=?)", id);
            jdbc.update("delete from community_collections where owner_id=?", id);
            jdbc.update("delete from article_drafts where author_id=?", id);
            jdbc.update("delete from content_posts where author_id=?", id);
        }
        TestReviewConfig.reset(jdbc);
    }

    JsonNode body(ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    long publishArticle(String title) throws Exception { return TestArticles.publish(mvc, json, jdbc, author.session(), title, "摘要", "正文"); }
    int topicCount() { return jdbc.queryForObject("select count(*) from content_posts where type<>'ARTICLE'", Integer.class); } // 话题类型已不存在：非文章行恒为0

    // ------------------------------------------------------------------ 旧接口停用、话题创建关闭
    @Test void legacyPostEndpointIsGoneForBothTypesAndNoTopicIsCreated() throws Exception {
        int topics = topicCount(), posts = jdbc.queryForObject("select count(*) from content_posts", Integer.class);
        for (String type : List.of("ARTICLE", "TOPIC")) {
            JsonNode r = body(mvc.perform(t.write(post("/api/posts"), author.session(), Map.of("type", type, "title", "t", "summary", "s", "body", "b"))).andExpect(status().isGone()));
            assertEquals("GONE", r.path("code").asText());
        }
        assertEquals(topics, topicCount(), "话题创建已关闭"); assertEquals(posts, jdbc.queryForObject("select count(*) from content_posts", Integer.class), "旧接口不再创建任何内容");
        mvc.perform(t.write(post("/api/posts"), restricted.session(), Map.of("type", "TOPIC", "title", "t", "summary", "s", "body", "b"))).andExpect(status().isForbidden()); // 权限检查仍先于此处
        mvc.perform(t.write(post("/api/posts"), null, Map.of("type", "TOPIC", "title", "t", "summary", "s", "body", "b"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/posts")).andExpect(status().isOk()); // 既有内容（含话题）仍可读：阶段1不改读取
    }

    // ------------------------------------------------------------------ 作者列表与资料计数只含可见内容
    @Test void authorListAndProfileCountOnlyIncludeVisibleContent() throws Exception {
        long visible = publishArticle("一直可见"), withdrawn = publishArticle("将被撤回"), down = publishArticle("将被下架");
        assertEquals(3, body(mvc.perform(t.read(get("/api/me/posts"), author.session())).andExpect(status().isOk())).path("items").size());
        assertEquals(3, body(mvc.perform(t.read(get("/api/me/profile"), author.session())).andExpect(status().isOk())).path("postCount").asInt());

        long draft = jdbc.queryForObject("select id from article_drafts where post_id=?", Long.class, withdrawn);
        mvc.perform(t.write(post("/api/me/articles/" + draft + "/withdraw"), author.session(), Map.of("confirm", true))).andExpect(status().isOk());
        mvc.perform(t.write(post("/api/review/publications/ARTICLE/" + down), owner.session(), Map.of("action", "TAKE_DOWN", "expectedModeration", "VISIBLE", "note", "测试下架"))).andExpect(status().isOk());

        JsonNode mine = body(mvc.perform(t.read(get("/api/me/posts"), author.session())).andExpect(status().isOk()));
        assertEquals(1, mine.path("items").size()); assertEquals(visible, mine.path("items").get(0).path("id").asLong(), "已撤回/已下架不再出现在作者的已发布列表");
        assertEquals(1, body(mvc.perform(t.read(get("/api/me/profile"), author.session())).andExpect(status().isOk())).path("postCount").asInt());
    }

    // ------------------------------------------------------------------ 专题收录
    long collection() throws Exception {
        JsonNode c = body(mvc.perform(t.write(post("/api/collections"), author.session(), Map.of("name", "可见性专题", "description", "验证收录"))).andExpect(status().isCreated()));
        return c.path("id").asLong();
    }
    ResultActions add(long collectionId, long postId) throws Exception {
        long revision = jdbc.queryForObject("select revision from community_collections where id=?", Long.class, collectionId);
        return mvc.perform(t.write(put("/api/me/collections/" + collectionId + "/posts/" + postId).param("revision", String.valueOf(revision)), author.session(), null));
    }

    @Test void onlyVisibleOwnArticlesCanBeAddedToACollectionAndNonVisibleOnesAreFrozenAsUnavailable() throws Exception {
        long collection = collection();
        long ok = publishArticle("可收录"), withdrawn = publishArticle("撤回后不可收录"), down = publishArticle("下架后不可收录");
        add(collection, ok).andExpect(status().isOk());

        long draft = jdbc.queryForObject("select id from article_drafts where post_id=?", Long.class, withdrawn);
        mvc.perform(t.write(post("/api/me/articles/" + draft + "/withdraw"), author.session(), Map.of("confirm", true))).andExpect(status().isOk());
        mvc.perform(t.write(post("/api/review/publications/ARTICLE/" + down), owner.session(), Map.of("action", "TAKE_DOWN", "expectedModeration", "VISIBLE", "note", "测试下架"))).andExpect(status().isOk());
        assertEquals(400, add(collection, withdrawn).andReturn().getResponse().getStatus(), "已撤回的文章不能收录");
        assertEquals(400, add(collection, down).andReturn().getResponse().getStatus(), "已下架的文章不能收录");
        assertEquals(1, jdbc.queryForObject("select count(*) from collection_entries where collection_id=?", Integer.class, collection));
        add(collection, 999_999_999L).andExpect(status().isNotFound());

        // 已收录的文章之后被撤回：快照捕获只留占位（标题/摘要为空），不把原文冻结进快照
        mvc.perform(t.write(post("/api/me/articles/" + jdbc.queryForObject("select id from article_drafts where post_id=?", Long.class, ok) + "/withdraw"), author.session(), Map.of("confirm", true))).andExpect(status().isOk());
        List<PublicEntry> entries = publications.capture(collection).sections().stream().flatMap(s -> s.entries().stream()).toList();
        PublicEntry entry = entries.stream().filter(e -> Long.valueOf(ok).equals(e.postId())).findFirst().orElseThrow();
        assertNull(entry.postTitle(), "撤回后的文章标题不得被冻结进快照"); assertNull(entry.postSummary());
    }

    // ------------------------------------------------------------------ 删除后的公开专题（V19 删除话题/文章后的读取行为）
    @Test void aPublicCollectionNeverShowsTheFrozenTitleOfAPostThatWasDeleted() throws Exception {
        long collection = collection();
        long post = publishArticle("将被物理删除的文章");
        add(collection, post).andExpect(status().isOk());
        jdbc.update("update community_collections set status='PUBLISHED' where id=?", collection);
        publications.publishSnapshot(collection);
        assertTrue(mvc.perform(get("/api/collections/" + collection + "/outline")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).contains("将被物理删除的文章"), "删除前读者能看到引用的标题");

        jdbc.update("delete from article_drafts where post_id=?", post);
        jdbc.update("delete from content_posts where id=?", post);
        String after = mvc.perform(get("/api/collections/" + collection + "/outline")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(after.contains("将被物理删除的文章"), "快照里冻结的旧标题不得再展示: " + after);
    }
}
