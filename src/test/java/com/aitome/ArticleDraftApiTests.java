package com.aitome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FINNS-18 B2（契约修订2 §3.0–§3.2、§4、§14 R9）：草稿的创建/读取/保存/列表。H2 内存库；CT-A01/A02/A10/A13 的后端部分。PostgreSQL 语义未验（B7）。 */
@SpringBootTest @AutoConfigureMockMvc
class ArticleDraftApiTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    AccessTestSupport t;
    AccessTestSupport.Account author, other;
    final List<Long> authorIds = new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        t = new AccessTestSupport(mvc, json, jdbc);
        author = t.register("b2author"); other = t.register("b2other");
        authorIds.add(author.id()); authorIds.add(other.id());
    }

    @AfterEach void cleanup() {
        for (long id : authorIds) {
            jdbc.update("delete from review_flow_events where flow_id in (select id from review_flows where author_id=?)", id);
            jdbc.update("delete from review_flows where author_id=?", id);
            jdbc.update("delete from review_submissions where author_id=?", id);
            jdbc.update("delete from review_content_state where author_id=?", id);
            jdbc.update("delete from article_drafts where author_id=?", id);
            jdbc.update("delete from content_posts where author_id=?", id);
        }
    }

    // ------------------------------------------------------------------ 辅助
    JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception { return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    org.springframework.test.web.servlet.ResultActions create(AccessTestSupport.Account a, Object body) throws Exception { return mvc.perform(t.write(post("/api/me/articles"), a.session(), body)); }
    org.springframework.test.web.servlet.ResultActions save(AccessTestSupport.Account a, long id, Object body) throws Exception { return mvc.perform(t.write(put("/api/me/articles/" + id), a.session(), body)); }
    org.springframework.test.web.servlet.ResultActions read(AccessTestSupport.Account a, String path) throws Exception { return mvc.perform(t.read(get(path), a == null ? null : a.session())); }
    long draft(AccessTestSupport.Account a, String title, String summary, String text) throws Exception { return body(create(a, Map.of("title", title, "summary", summary, "body", text)).andExpect(status().isCreated())).path("id").asLong(); }
    JsonNode view(AccessTestSupport.Account a, long id) throws Exception { return body(read(a, "/api/me/articles/" + id).andExpect(status().isOk())); }

    // ------------------------------------------------------------------ CT-A01：创建/读取/归属/会话/CSRF
    @Test void createReadAndOwnership() throws Exception {
        JsonNode created = body(create(author, Map.of("title", "我的第一篇", "summary", "摘要", "body", "# 正文")).andExpect(status().isCreated()));
        long id = created.path("id").asLong();
        assertEquals(1, created.path("version").asInt()); assertTrue(created.path("postId").isNull());
        assertEquals("NONE", created.path("status").path("publicState").asText()); assertEquals("PRIVATE", created.path("status").path("work").asText());
        assertTrue(created.path("canDelete").asBoolean()); assertFalse(created.path("canCancel").asBoolean()); assertFalse(created.path("canWithdraw").asBoolean());
        assertTrue(created.path("published").isNull()); assertTrue(created.path("submitted").isNull()); assertEquals(0, created.path("flows").size());
        assertEquals("# 正文", view(author, id).path("body").asText(), "刷新读取能恢复已保存的工作稿");

        read(other, "/api/me/articles/" + id).andExpect(status().isNotFound());
        save(other, id, Map.of("title", "x", "summary", "", "body", "", "expectedVersion", 1)).andExpect(status().isNotFound());
        assertEquals("我的第一篇", jdbc.queryForObject("select title from article_drafts where id=?", String.class, id), "他人的写入不得生效");
        assertEquals(0, body(read(other, "/api/me/articles")).path("total").asInt(), "他人看不到我的稿件");
        read(null, "/api/me/articles/" + id).andExpect(status().isUnauthorized());
        read(null, "/api/me/articles").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/articles").session(author.session()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"无令牌\"}")).andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("select count(*) from article_drafts where author_id=?", Integer.class, author.id()), "缺 CSRF 的请求不得建稿");
    }

    // ------------------------------------------------------------------ CT-A02：空稿、必填、上限（码点）
    @Test void emptyAndLimits() throws Exception {
        create(author, Map.of("title", "  ", "summary", "", "body", "\n")).andExpect(status().isBadRequest());
        assertEquals("EMPTY_DRAFT", body(create(author, Map.of("title", "", "summary", "", "body", ""))).path("code").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from article_drafts where author_id=?", Integer.class, author.id()));
        create(author, Map.of("title", "只有标题")).andExpect(status().isCreated());
        create(author, Map.of("body", "只有正文")).andExpect(status().isCreated());

        create(author, Map.of("title", "😀".repeat(120), "summary", "😀".repeat(280), "body", "😀".repeat(20000))).andExpect(status().isCreated());
        JsonNode tooLong = body(create(author, Map.of("title", "😀".repeat(121))).andExpect(status().isBadRequest()));
        assertEquals("TOO_LONG", tooLong.path("code").asText()); assertEquals("title", tooLong.path("field").asText()); assertEquals(120, tooLong.path("max").asInt()); assertEquals(121, tooLong.path("actual").asInt());
        assertEquals("summary", body(create(author, Map.of("summary", "x".repeat(281))).andExpect(status().isBadRequest())).path("field").asText());
        assertEquals("body", body(create(author, Map.of("body", "x".repeat(20001))).andExpect(status().isBadRequest())).path("field").asText());
        long id = draft(author, "t", "s", "b");
        assertEquals("EMPTY_DRAFT", body(save(author, id, Map.of("title", "", "summary", " ", "body", "", "expectedVersion", 1)).andExpect(status().isBadRequest())).path("code").asText());
        assertEquals("t", view(author, id).path("title").asText(), "被拒绝的保存不得改动草稿");
    }

    // ------------------------------------------------------------------ 保存与乐观版本
    @Test void saveBumpsVersionOnlyOnChangeAndRejectsStaleVersions() throws Exception {
        long id = draft(author, "标题", "摘要", "正文");
        assertEquals(1, body(save(author, id, Map.of("title", "标题", "summary", "摘要", "body", "正文", "expectedVersion", 1)).andExpect(status().isOk())).path("version").asInt(), "内容未变不加版本");
        JsonNode changed = body(save(author, id, Map.of("title", "标题2", "summary", "摘要", "body", "正文", "expectedVersion", 1)).andExpect(status().isOk()));
        assertEquals(2, changed.path("version").asInt()); assertEquals("标题2", view(author, id).path("title").asText());

        JsonNode stale = body(save(author, id, Map.of("title", "旧窗口", "summary", "", "body", "", "expectedVersion", 1)).andExpect(status().isConflict()));
        assertEquals("VERSION_CONFLICT", stale.path("code").asText()); assertEquals(2, stale.path("currentVersion").asInt());
        assertEquals("标题2", view(author, id).path("title").asText(), "冲突时不覆盖别处的保存");
        assertEquals("VALIDATION", body(save(author, id, Map.of("title", "x", "summary", "", "body", "")).andExpect(status().isBadRequest())).path("code").asText());
        assertEquals(2, view(author, id).path("version").asInt());
    }

    // ------------------------------------------------------------------ CT-A10：权限分工（PUBLISH-D09）
    @Test void permissionSplitKeepsReadingForRevokedAuthors() throws Exception {
        AccessTestSupport.Account restricted = t.withRole("b2res", "RESTRICTED"); authorIds.add(restricted.id());
        create(restricted, Map.of("title", "不能建")).andExpect(status().isForbidden());
        assertEquals(0, body(read(restricted, "/api/me/articles").andExpect(status().isOk())).path("total").asInt());

        long id = draft(author, "已有草稿", "", "");
        t.setRole(author.id(), "RESTRICTED"); // 作者被撤销“发布文章”权限
        assertEquals("已有草稿", view(author, id).path("title").asText(), "被撤权后仍可读取自己的稿件（profile.manage）");
        assertEquals(1, body(read(author, "/api/me/articles").andExpect(status().isOk())).path("total").asInt());
        save(author, id, Map.of("title", "改", "summary", "", "body", "", "expectedVersion", 1)).andExpect(status().isForbidden());
        create(author, Map.of("title", "新稿")).andExpect(status().isForbidden());
        t.setRole(author.id(), "MEMBER");
    }

    // ------------------------------------------------------------------ CT-A13：列表、筛选、搜索、分页
    @Test void listFiltersSearchesAndPages() throws Exception {
        for (int i = 1; i <= 5; i++) draft(author, "alpha-" + i, "第" + i + "篇 摘要", "正文" + i);
        draft(author, "Beta 文章", "含关键字 Needle", "正文");
        assertEquals(6, body(read(author, "/api/me/articles")).path("total").asInt());
        assertEquals(6, body(read(author, "/api/me/articles?state=DRAFT")).path("total").asInt());
        assertEquals(0, body(read(author, "/api/me/articles?state=LIVE")).path("total").asInt());
        assertEquals(0, body(read(author, "/api/me/articles?state=OFF")).path("total").asInt());
        assertEquals(1, body(read(author, "/api/me/articles?q=needle")).path("total").asInt(), "搜索不区分大小写，覆盖摘要");
        assertEquals(5, body(read(author, "/api/me/articles?q=ALPHA")).path("total").asInt());
        JsonNode page = body(read(author, "/api/me/articles?size=2&page=1"));
        assertEquals(2, page.path("items").size()); assertTrue(page.path("hasMore").asBoolean()); assertEquals(6, page.path("total").asInt());
        JsonNode last = body(read(author, "/api/me/articles?size=2&page=2"));
        assertEquals(2, last.path("items").size()); assertFalse(last.path("hasMore").asBoolean());
        assertEquals(50, body(read(author, "/api/me/articles?size=999")).path("size").asInt(), "size 上限 50");
        read(author, "/api/me/articles?state=BOGUS").andExpect(status().isBadRequest());
        long recent = draft(author, "最新保存", "", "");
        assertEquals(recent, body(read(author, "/api/me/articles")).path("items").get(0).path("id").asLong(), "按更新时间倒序");
    }

    // ------------------------------------------------------------------ 状态推导（契约 §4.1、§14 R9、S3）
    long link(long draftId, String moderation, String title, String summary, String text) {
        jdbc.update("insert into content_posts(type,title,summary,body,author_id,author_name,created_at,likes,moderation_state) values('ARTICLE',?,?,?,?, 'x', current_timestamp, 0, ?)", title, summary, text, author.id(), moderation);
        long postId = jdbc.queryForObject("select max(id) from content_posts where author_id=?", Long.class, author.id());
        jdbc.update("update article_drafts set post_id=? where id=?", postId, draftId);
        return postId;
    }
    long state(long draftId, Long target) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into review_content_state(author_id,content_type,content_key,target_id,attempts,generation,approved_once,legacy,created_at,updated_at) values(?,'ARTICLE',?,?,0,1,true,false,?,?)", author.id(), "ARTICLE#" + draftId, target, now, now);
        return jdbc.queryForObject("select id from review_content_state where author_id=? and content_key=?", Long.class, author.id(), "ARTICLE#" + draftId);
    }
    long flow(long stateId, String status, long version) {
        jdbc.update("insert into review_flows(state_id,author_id,content_type,title,snapshot_version,level,status,started_at) values(?,?,'ARTICLE','t',?,'NONE',?,?)", stateId, author.id(), version, status, Timestamp.from(Instant.now()));
        long id = jdbc.queryForObject("select max(id) from review_flows where state_id=?", Long.class, stateId);
        jdbc.update("insert into review_flow_events(flow_id,stage,title,note,detail,occurred_at) values(?,'SUBMIT','已提交','',?,?)", id, "", Timestamp.from(Instant.now()));
        return id;
    }
    void pending(long stateId, long flowId, String kind, String level, String snapshot) {
        jdbc.update("insert into review_submissions(state_id,author_id,content_type,title,generation,snapshot_json,snapshot_version,triggers,matched_phrases,attempts,kind,level,status,result_note,flow_id,created_at) values(?,?,'ARTICLE','t',2,?,5,'SENSITIVE','命中词',0,?,?,'PENDING','',?,?)", stateId, author.id(), snapshot, kind, level, flowId, Timestamp.from(Instant.now()));
    }

    @Test void statusDerivationCoversPublicAndWorkStates() throws Exception {
        long id = draft(author, "公开标题", "公开摘要", "公开正文");
        link(id, "VISIBLE", "公开标题", "公开摘要", "公开正文");
        JsonNode live = view(author, id);
        assertEquals("LIVE", live.path("status").path("publicState").asText()); assertEquals("SAVED", live.path("status").path("work").asText());
        assertTrue(live.path("canWithdraw").asBoolean()); assertFalse(live.path("canDelete").asBoolean());
        assertEquals("公开正文", live.path("published").path("body").asText()); assertTrue(live.path("published").path("version").isNull());

        save(author, id, Map.of("title", "公开标题", "summary", "公开摘要", "body", "改过的正文", "expectedVersion", 1)).andExpect(status().isOk());
        assertEquals("MODIFIED", view(author, id).path("status").path("work").asText(), "草稿与公开版不同");
        assertEquals("公开正文", view(author, id).path("published").path("body").asText(), "公开版不随工作稿变化");
        assertEquals(1, body(read(author, "/api/me/articles?state=LIVE")).path("total").asInt());
        assertEquals(0, body(read(author, "/api/me/articles?state=DRAFT")).path("total").asInt(), "已公开的不算私人草稿");

        long stateId = state(id, jdbc.queryForObject("select post_id from article_drafts where id=?", Long.class, id));
        long published = flow(stateId, "PUBLISHED", 7);
        assertEquals(7, view(author, id).path("published").path("version").asInt(), "公开版版本取最近一条 PUBLISHED 流转");
        pending(stateId, published, "REVIEW", "MEDIUM", "{\"title\":\"待审标题\",\"summary\":\"s\",\"body\":\"待审正文\"}");
        JsonNode reviewing = view(author, id);
        assertEquals("REVIEWING", reviewing.path("status").path("work").asText()); assertTrue(reviewing.path("canCancel").asBoolean());
        assertEquals("待审标题", reviewing.path("submitted").path("title").asText()); assertEquals("MEDIUM", reviewing.path("submitted").path("level").asText());
        assertFalse(reviewing.toString().contains("命中词"), "不得泄露命中词");
        assertEquals(1, body(read(author, "/api/me/articles?state=REVIEW")).path("total").asInt());
        assertEquals(1, body(read(author, "/api/me/articles?state=LIVE")).path("total").asInt(), "同一文章可同时属于 LIVE 与 REVIEW");

        jdbc.update("update review_submissions set kind='RECHECK', level='HIGH' where state_id=?", stateId);
        JsonNode recheck = view(author, id);
        assertEquals("RECHECK", recheck.path("status").path("work").asText()); assertFalse(recheck.path("canCancel").asBoolean(), "高敏感复核不可取消");
        assertEquals("HIGH", recheck.path("submitted").path("level").asText());

        jdbc.update("update review_submissions set status='INVALID' where state_id=?", stateId);
        long rejected = flow(stateId, "REJECTED", 8);
        assertTrue(rejected > published);
        assertEquals("REJECTED", view(author, id).path("status").path("work").asText());
        jdbc.update("update content_posts set moderation_state='WITHDRAWN' where id=(select post_id from article_drafts where id=?)", id);
        JsonNode withdrawn = view(author, id);
        assertEquals("WITHDRAWN", withdrawn.path("status").path("publicState").asText()); assertEquals("SAVED", withdrawn.path("status").path("work").asText(), "撤回后只显示已撤回+已保存");
        assertTrue(withdrawn.path("published").isNull()); assertFalse(withdrawn.path("canWithdraw").asBoolean()); assertFalse(withdrawn.path("canDelete").asBoolean());
        assertEquals(1, body(read(author, "/api/me/articles?state=OFF")).path("total").asInt());
        jdbc.update("update content_posts set moderation_state='TAKEN_DOWN' where id=(select post_id from article_drafts where id=?)", id);
        assertEquals("DOWN", view(author, id).path("status").path("publicState").asText());
        assertEquals(2, view(author, id).path("flows").size(), "流转列表按作者视角返回");
    }

    @Test void pendingSubmissionBlocksDeleteFlagForUnpublishedDraft() throws Exception {
        long id = draft(author, "未发布", "s", "b");
        long stateId = state(id, null);
        long f = flow(stateId, "IN_REVIEW", 1);
        assertEquals("SAVED", view(author, id).path("status").path("work").asText(), "有流转但无待办的未公开稿显示已保存");
        assertTrue(view(author, id).path("canDelete").asBoolean());
        pending(stateId, f, "REVIEW", "MEDIUM", "{\"title\":\"a\",\"summary\":\"b\",\"body\":\"c\"}");
        JsonNode v = view(author, id);
        assertFalse(v.path("canDelete").asBoolean(), "有待处理提交不可删除"); assertTrue(v.path("canCancel").asBoolean()); assertEquals("REVIEWING", v.path("status").path("work").asText());
    }
}
