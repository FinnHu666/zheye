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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FINNS-18 B3（契约修订2 §3.3–§3.10）：发布/更新、取消送审、撤回、删除，以及 decide/manage/点赞补强。H2 内存库。
 * 覆盖 CT-A05–A09、A11（H2 两线程）、A12（部分）、A14、A15。PostgreSQL 语义、行锁行为与迁移未验（B7）。
 */
@SpringBootTest @AutoConfigureMockMvc
class ArticleLifecycleTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    AccessTestSupport t;
    AccessTestSupport.Account owner, author, other;

    @BeforeEach void setUp() throws Exception {
        TestReviewConfig.reset(jdbc);
        t = new AccessTestSupport(mvc, json, jdbc);
        owner = t.register("lcowner"); t.setRole(owner.id(), "OWNER");
        author = t.register("lcauthor"); other = t.register("lcother");
        addRule("lowword", "LOW"); addRule("midword", "MEDIUM"); addRule("highword", "HIGH");
    }

    @AfterEach void tearDown() {
        for (long id : new long[]{owner.id(), author.id(), other.id()}) {
            jdbc.update("delete from comments where post_id in (select id from content_posts where author_id=?)", id);
            jdbc.update("delete from article_drafts where author_id=?", id);
            jdbc.update("delete from content_posts where author_id=?", id);
        }
        TestReviewConfig.reset(jdbc);
    }

    // ------------------------------------------------------------------ 辅助
    void addRule(String phrase, String level) {
        jdbc.update("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,level,tag,version,created_at,updated_at) values(?,?,true,false,?,?,null,1,current_timestamp,current_timestamp)", phrase, "", phrase, level);
    }
    JsonNode body(ResultActions r) throws Exception {
        String text = r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }
    ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, AccessTestSupport.Account a, Object payload) throws Exception { return mvc.perform(t.write(b, a == null ? null : a.session(), payload)); }
    ResultActions fetch(AccessTestSupport.Account a, String path) throws Exception { return mvc.perform(t.read(get(path), a == null ? null : a.session())); }
    long draft(AccessTestSupport.Account a, String title, String summary, String text) throws Exception {
        return body(call(post("/api/me/articles"), a, Map.of("title", title, "summary", summary, "body", text)).andExpect(status().isCreated())).path("id").asLong();
    }
    JsonNode view(AccessTestSupport.Account a, long id) throws Exception { return body(fetch(a, "/api/me/articles/" + id).andExpect(status().isOk())); }
    ResultActions publish(AccessTestSupport.Account a, long id, String title, String summary, String text, long version, String requestId) throws Exception {
        return call(post("/api/me/articles/" + id + "/publish"), a, Map.of("title", title, "summary", summary, "body", text, "expectedVersion", version, "requestId", requestId));
    }
    /** 用当前草稿内容发布，新 requestId。 */
    JsonNode publishNow(AccessTestSupport.Account a, long id, int expectedStatus) throws Exception {
        JsonNode v = view(a, id);
        return body(publish(a, id, v.path("title").asText(), v.path("summary").asText(), v.path("body").asText(), v.path("version").asLong(), UUID.randomUUID().toString()).andExpect(status().is(expectedStatus)));
    }
    JsonNode edit(AccessTestSupport.Account a, long id, String text) throws Exception {
        JsonNode v = view(a, id);
        return body(call(put("/api/me/articles/" + id), a, Map.of("title", v.path("title").asText(), "summary", v.path("summary").asText(), "body", text, "expectedVersion", v.path("version").asLong())).andExpect(status().isOk()));
    }
    JsonNode decide(long task, String action, String note, int expected) throws Exception {
        long generation = jdbc.queryForObject("select generation from review_submissions where id=?", Long.class, task);
        return body(call(post("/api/review/tasks/" + task), owner, Map.of("action", action, "expectedGeneration", generation, "note", note)).andExpect(status().is(expected)));
    }
    JsonNode manage(long postId, String action, String expectedModeration, int expected) throws Exception {
        return body(call(post("/api/review/publications/ARTICLE/" + postId), owner, Map.of("action", action, "expectedModeration", expectedModeration, "note", "测试处置")).andExpect(status().is(expected)));
    }
    long postOf(long draftId) { return jdbc.queryForObject("select post_id from article_drafts where id=?", Long.class, draftId); }
    String moderation(long postId) { return jdbc.queryForObject("select moderation_state from content_posts where id=?", String.class, postId); }
    long pendingTask() { return jdbc.queryForObject("select max(id) from review_submissions where status='PENDING'", Long.class); }
    int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    List<String> stages(long flowId) { return jdbc.queryForList("select title from review_flow_events where flow_id=? order by occurred_at,id", String.class, flowId); }

    // ------------------------------------------------------------------ CT-A05/A06：首次发布与更新同一公开行
    @Test void cleanFirstPublishLinksDraftAndUpdatesTheSamePostRowLater() throws Exception {
        long id = draft(author, "我的文章", "摘要", "第一版正文");
        JsonNode first = publishNow(author, id, 200);
        assertEquals("PUBLISHED", first.path("outcome").asText()); assertEquals("NONE", first.path("level").asText()); assertFalse(first.path("replay").asBoolean());
        assertEquals("LIVE", first.path("article").path("status").path("publicState").asText());
        long postId = postOf(id); assertEquals(postId, first.path("article").path("postId").asLong());
        JsonNode pub = body(fetch(null, "/api/posts/" + postId).andExpect(status().isOk()));
        assertEquals("第一版正文", pub.path("body").asText()); assertTrue(pub.path("updatedAt").isNull(), "从未被作者更新");
        long flowId = first.path("flowId").asLong(); assertEquals(List.of("已提交", "敏感检测完成", "直接发布", "已发布"), stages(flowId));
        assertEquals("ARTICLE#" + id, jdbc.queryForObject("select content_key from review_content_state where target_id=?", String.class, postId));
        java.time.Instant created = jdbc.queryForObject("select created_at from content_posts where id=?", java.sql.Timestamp.class, postId).toInstant();

        body(call(post("/api/posts/" + postId + "/like"), other, Map.of()).andExpect(status().isOk()));
        edit(author, id, "第二版正文，已修改");
        assertEquals("第一版正文", body(fetch(null, "/api/posts/" + postId)).path("body").asText(), "未发布更新前读者仍看到旧版");
        JsonNode second = publishNow(author, id, 200);
        assertEquals("PUBLISHED", second.path("outcome").asText()); assertEquals(postId, postOf(id), "更新同一公开行，不新建");
        assertEquals(1, count("select count(*) from content_posts where author_id=?", author.id()));
        JsonNode updated = body(fetch(null, "/api/posts/" + postId).andExpect(status().isOk()));
        assertEquals("第二版正文，已修改", updated.path("body").asText()); assertFalse(updated.path("updatedAt").isNull()); assertEquals(1, updated.path("likes").asInt(), "点赞保留");
        assertEquals(created, jdbc.queryForObject("select created_at from content_posts where id=?", java.sql.Timestamp.class, postId).toInstant(), "发布时间与排序不变");
    }

    @Test void lowSensitivityPublishesAndLeavesATrace() throws Exception {
        long id = draft(author, "低敏感", "摘要", "里面有 lowword 一词");
        JsonNode r = publishNow(author, id, 200);
        assertEquals("PUBLISHED", r.path("outcome").asText()); assertEquals("LOW", r.path("level").asText());
        assertEquals(1, count("select count(*) from review_noted where author_id=?", author.id()));
    }

    // ------------------------------------------------------------------ CT-A06/A07：中敏感送审、冻结快照、审批通过更新同一行
    @Test void mediumUpdateWaitsForReviewAndApprovalPublishesTheFrozenSnapshotOnly() throws Exception {
        long id = draft(author, "送审文章", "摘要", "干净的第一版");
        publishNow(author, id, 200); long postId = postOf(id);
        edit(author, id, "更新稿含 midword");
        JsonNode pending = publishNow(author, id, 200);
        assertEquals("PENDING", pending.path("outcome").asText()); assertEquals("REVIEWING", pending.path("article").path("status").path("work").asText());
        assertEquals("干净的第一版", body(fetch(null, "/api/posts/" + postId)).path("body").asText(), "待审期间旧公开版继续可读");
        edit(author, id, "送审之后又改了一版");
        JsonNode view = view(author, id);
        assertEquals("更新稿含 midword", view.path("submitted").path("body").asText(), "冻结的提交版不随工作稿变化");
        assertEquals("送审之后又改了一版", view.path("body").asText());

        long task = pendingTask();
        decide(task, "APPROVE", "", 200);
        JsonNode pub = body(fetch(null, "/api/posts/" + postId).andExpect(status().isOk()));
        assertEquals("更新稿含 midword", pub.path("body").asText(), "审批通过发布的是提交快照，而不是之后的工作稿");
        assertFalse(pub.path("updatedAt").isNull()); assertEquals(postId, postOf(id)); assertEquals(1, count("select count(*) from content_posts where author_id=?", author.id()));
        JsonNode after = view(author, id);
        assertEquals("MODIFIED", after.path("status").path("work").asText(), "工作稿与新公开版不同");
        decide(task, "APPROVE", "", 409);
    }

    @Test void firstPublicationApprovedByAReviewerWritesTheLinkBackToTheDraft() throws Exception {
        long id = draft(author, "首次送审", "摘要", "含 midword 的正文");
        assertEquals("PENDING", publishNow(author, id, 200).path("outcome").asText());
        assertNull(jdbc.queryForObject("select post_id from article_drafts where id=?", Long.class, id));
        assertEquals(0, count("select count(*) from content_posts where author_id=?", author.id()));
        decide(pendingTask(), "APPROVE", "", 200);
        long postId = postOf(id);
        assertEquals(postId, jdbc.queryForObject("select target_id from review_content_state where content_key=?", Long.class, "ARTICLE#" + id));
        assertEquals("含 midword 的正文", body(fetch(null, "/api/posts/" + postId)).path("body").asText());
        assertEquals("LIVE", view(author, id).path("status").path("publicState").asText());
    }

    // ------------------------------------------------------------------ CT-A05/A07：高敏感不可取消，改稿重提
    @Test void highSensitivityIsRejectedAutomaticallyCannotBeCancelledAndCanBeRevised() throws Exception {
        long id = draft(author, "高敏感", "摘要", "这里有 highword");
        JsonNode r = publishNow(author, id, 200);
        assertEquals("AUTO_REJECTED", r.path("outcome").asText()); assertEquals("HIGH", r.path("level").asText());
        assertFalse(r.path("article").path("flows").toString().contains("命中"), "流转事件不得向作者展示命中词");
        assertFalse(r.has("matches") || r.path("article").has("matches"), "响应不含命中词字段");
        assertFalse(r.path("article").path("canCancel").asBoolean()); assertEquals("RECHECK", r.path("article").path("status").path("work").asText());
        assertEquals("HIGH", r.path("article").path("submitted").path("level").asText());
        assertEquals("RECHECK_NOT_CANCELLABLE", body(call(post("/api/me/articles/" + id + "/cancel"), author, Map.of()).andExpect(status().isConflict())).path("code").asText());
        long task = pendingTask();
        assertEquals("RECHECK_NOT_CANCELLABLE", body(call(post("/api/review/mine/" + task + "/withdraw"), author, Map.of()).andExpect(status().isConflict())).path("code").asText(), "旧撤回入口对文章复核任务同样保护");
        assertEquals(1, count("select count(*) from review_submissions where id=? and status='PENDING'", task));
        edit(author, id, "改成了干净的正文");
        assertEquals("PUBLISHED", publishNow(author, id, 200).path("outcome").asText());
        assertEquals("INVALID", jdbc.queryForObject("select status from review_submissions where id=?", String.class, task), "重新提交使旧复核任务失效");
        assertEquals(0, count("select count(*) from content_posts where body like '%highword%'"));
    }

    // ------------------------------------------------------------------ CT-A07：取消送审只作废本次提交
    @Test void cancellingASubmissionKeepsTheDraftAndTheOldPublicVersion() throws Exception {
        long id = draft(author, "取消送审", "摘要", "旧公开版正文");
        publishNow(author, id, 200); long postId = postOf(id);
        edit(author, id, "更新含 midword");
        JsonNode pending = publishNow(author, id, 200); long task = pendingTask(); long flowId = pending.path("flowId").asLong();
        JsonNode cancelled = body(call(post("/api/me/articles/" + id + "/cancel"), author, Map.of()).andExpect(status().isOk()));
        assertEquals("INVALID", jdbc.queryForObject("select status from review_submissions where id=?", String.class, task));
        assertEquals("INVALID", jdbc.queryForObject("select status from review_flows where id=?", String.class, flowId));
        assertTrue(stages(flowId).contains("已取消送审"), "作者可见事件");
        assertEquals("更新含 midword", cancelled.path("article").path("body").asText(), "工作稿保留");
        assertEquals("旧公开版正文", body(fetch(null, "/api/posts/" + postId)).path("body").asText());
        assertEquals("NOTHING_TO_CANCEL", body(call(post("/api/me/articles/" + id + "/cancel"), author, Map.of()).andExpect(status().isConflict())).path("code").asText());
        decide(task, "APPROVE", "", 409);
    }

    // ------------------------------------------------------------------ CT-A08：撤回与重新公开
    @Test void withdrawalNeedsConfirmationHidesTheArticleAndRepublishingIsDetectedAgain() throws Exception {
        long id = draft(author, "撤回文章", "摘要", "公开正文");
        publishNow(author, id, 200); long postId = postOf(id);
        assertEquals("CONFIRM_REQUIRED", body(call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of()).andExpect(status().isBadRequest())).path("code").asText());
        assertEquals("VISIBLE", moderation(postId));
        edit(author, id, "撤回前的更新含 midword"); publishNow(author, id, 200); long pendingTask = pendingTask();

        JsonNode w = body(call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isOk()));
        assertEquals("WITHDRAWN", moderation(postId)); assertEquals("WITHDRAWN", w.path("article").path("status").path("publicState").asText()); assertEquals("SAVED", w.path("article").path("status").path("work").asText());
        assertEquals("INVALID", jdbc.queryForObject("select status from review_submissions where id=?", String.class, pendingTask), "待审提交随撤回失效");
        fetch(null, "/api/posts/" + postId).andExpect(status().isNotFound());
        assertFalse(body(fetch(null, "/api/posts?size=50")).toString().contains("撤回文章"));
        call(post("/api/posts/" + postId + "/like"), other, Map.of()).andExpect(status().isNotFound());
        call(post("/api/posts/" + postId + "/comments"), other, Map.of("body", "评论")).andExpect(status().isNotFound());
        assertTrue(w.path("article").path("flows").toString().contains("作者撤回"));
        assertEquals("NOT_PUBLIC", body(call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isConflict())).path("code").asText());
        decide(pendingTask, "APPROVE", "", 409);
        fetch(null, "/api/posts/" + postId).andExpect(status().isNotFound());

        edit(author, id, "重新公开前含 highword");
        assertEquals("AUTO_REJECTED", publishNow(author, id, 200).path("outcome").asText(), "重新公开重新检测");
        fetch(null, "/api/posts/" + postId).andExpect(status().isNotFound());
        edit(author, id, "重新公开的干净正文");
        assertEquals("PUBLISHED", publishNow(author, id, 200).path("outcome").asText());
        assertEquals(postId, postOf(id)); assertEquals("VISIBLE", moderation(postId));
        assertEquals("重新公开的干净正文", body(fetch(null, "/api/posts/" + postId).andExpect(status().isOk())).path("body").asText());
    }

    @Test void withdrawingAHistoricalArticleWithoutFlowsCreatesASyntheticFlowForTheEvent() throws Exception {
        long id = draft(author, "历史文章", "摘要", "历史正文");
        jdbc.update("insert into content_posts(type,title,summary,body,author_id,author_name,created_at,likes,moderation_state) values('ARTICLE','历史文章','摘要','历史正文',?,'x',current_timestamp,0,'VISIBLE')", author.id());
        long postId = jdbc.queryForObject("select max(id) from content_posts where author_id=?", Long.class, author.id());
        jdbc.update("update article_drafts set post_id=? where id=?", postId, id);
        assertEquals(0, count("select count(*) from review_flows where author_id=?", author.id()));
        JsonNode w = body(call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isOk()));
        assertEquals(1, count("select count(*) from review_flows where author_id=? and status='PUBLISHED'", author.id()));
        assertEquals(1, w.path("article").path("flows").size()); assertTrue(w.path("article").path("flows").toString().contains("作者撤回"));
        assertEquals(0, count("select count(*) from review_flow_events where title in ('已提交','敏感检测完成') and flow_id in (select id from review_flows where author_id=?)", author.id()), "不补造提交/检测记录");
    }

    // ------------------------------------------------------------------ CT-A09：下架独立，不被作者动作解除
    @Test void manualTakedownIsNotUndoneByAuthorActionsAndWithdrawnContentCannotBeManaged() throws Exception {
        long id = draft(author, "下架文章", "摘要", "公开正文");
        publishNow(author, id, 200); long postId = postOf(id);
        manage(postId, "TAKE_DOWN", "VISIBLE", 200);
        assertEquals("TAKEN_DOWN", moderation(postId)); assertEquals("DOWN", view(author, id).path("status").path("publicState").asText());
        body(call(put("/api/me/articles/" + id), author, Map.of("title", "下架文章", "summary", "摘要", "body", "下架后仍可保存", "expectedVersion", 1)).andExpect(status().isOk()));
        edit(author, id, "再改一次");
        JsonNode v = view(author, id);
        assertEquals("TAKEN_DOWN", body(publish(author, id, v.path("title").asText(), v.path("summary").asText(), v.path("body").asText(), v.path("version").asLong(), "r-down").andExpect(status().isConflict())).path("code").asText());
        assertEquals("TAKEN_DOWN", body(call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isConflict())).path("code").asText());
        assertEquals("TAKEN_DOWN", moderation(postId), "作者动作不能解除下架");
        fetch(null, "/api/posts/" + postId).andExpect(status().isNotFound());
        manage(postId, "RESTORE", "TAKEN_DOWN", 200);
        assertEquals("VISIBLE", moderation(postId));

        long id2 = draft(author, "撤回后处置", "摘要", "正文2");
        publishNow(author, id2, 200); long post2 = postOf(id2);
        call(post("/api/me/articles/" + id2 + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isOk());
        assertEquals("NOT_PUBLIC", manage(post2, "TAKE_DOWN", "WITHDRAWN", 409).path("code").asText());
        assertEquals("NOT_PUBLIC", manage(post2, "RESTORE", "WITHDRAWN", 409).path("code").asText());
        assertEquals("WITHDRAWN", moderation(post2));
        assertFalse(body(fetch(owner, "/api/review/publications?size=50")).toString().contains("撤回后处置"), "审批侧列表不展示已撤回");
        fetch(owner, "/api/review/publications/ARTICLE/" + post2).andExpect(status().isNotFound());
    }

    @Test void approvingAnUpdateAfterATakedownIsAbortedAndDoesNotOverwriteTheTakedown() throws Exception {
        long id = draft(author, "下架前待审", "摘要", "旧公开正文");
        publishNow(author, id, 200); long postId = postOf(id);
        edit(author, id, "待审更新 midword"); publishNow(author, id, 200); long task = pendingTask();
        manage(postId, "TAKE_DOWN", "VISIBLE", 200);
        long generation = jdbc.queryForObject("select generation from review_submissions where id=?", Long.class, task);
        int status = call(post("/api/review/tasks/" + task), owner, Map.of("action", "APPROVE", "expectedGeneration", generation, "note", "")).andReturn().getResponse().getStatus();
        assertTrue(status == 200 || status == 409, "下架后批准要么被中止（200 带说明）要么按过期处理（409）: " + status);
        assertEquals("TAKEN_DOWN", moderation(postId));
        assertEquals("旧公开正文", jdbc.queryForObject("select body from content_posts where id=?", String.class, postId));
        assertNotEquals("PENDING", jdbc.queryForObject("select status from review_submissions where id=?", String.class, task));
    }

    // ------------------------------------------------------------------ CT-A05/A11：幂等与检测不可用
    @Test void sameRequestIdReplaysWithoutPublishingTwiceAndIsRejectedWithDifferentContent() throws Exception {
        long id = draft(author, "幂等", "摘要", "正文");
        JsonNode first = body(publish(author, id, "幂等", "摘要", "正文", 1, "req-1").andExpect(status().isOk()));
        JsonNode replay = body(publish(author, id, "幂等", "摘要", "正文", 1, "req-1").andExpect(status().isOk()));
        assertFalse(first.path("replay").asBoolean()); assertTrue(replay.path("replay").asBoolean());
        assertEquals(first.path("flowId").asLong(), replay.path("flowId").asLong());
        assertEquals(1, count("select count(*) from content_posts where author_id=?", author.id()));
        assertEquals(1, view(author, id).path("version").asInt(), "回放不改版本");
        assertEquals(1, count("select count(*) from review_flows where author_id=?", author.id()));
        assertEquals("REQUEST_REUSED", body(publish(author, id, "幂等", "摘要", "别的正文", 1, "req-1").andExpect(status().isConflict())).path("code").asText());
    }

    @Test void detectionUnavailableKeepsTheSavedDraftAndTheSameRequestCanBeRetried() throws Exception {
        long id = draft(author, "检测不可用", "摘要", "旧正文");
        publishNow(author, id, 200); long postId = postOf(id);
        jdbc.update("update sensitive_rules set enabled=false");
        JsonNode r = body(publish(author, id, "检测不可用", "摘要", "新正文", 1, "req-503").andExpect(status().isServiceUnavailable()));
        assertEquals("DETECTION_UNAVAILABLE", r.path("code").asText());
        JsonNode v = view(author, id);
        assertEquals("新正文", v.path("body").asText(), "草稿已保存"); assertEquals(2, v.path("version").asInt());
        assertEquals("旧正文", jdbc.queryForObject("select body from content_posts where id=?", String.class, postId), "旧公开版不变");
        assertEquals(1, count("select count(*) from review_flows where author_id=?", author.id()), "没有为失败的提交建立流转");

        jdbc.update("update sensitive_rules set enabled=true");
        JsonNode retry = body(publish(author, id, "检测不可用", "摘要", "新正文", 1, "req-503").andExpect(status().isOk()));
        assertEquals("PUBLISHED", retry.path("outcome").asText()); assertEquals(2, view(author, id).path("version").asInt(), "同一请求重试不再加版本");
        assertEquals("新正文", jdbc.queryForObject("select body from content_posts where id=?", String.class, postId));
    }

    @Test void retryWithTheSameRequestIdAfterAnotherSaveIsAVersionConflict() throws Exception {
        long id = draft(author, "重试冲突", "摘要", "正文");
        jdbc.update("update sensitive_rules set enabled=false");
        publish(author, id, "重试冲突", "摘要", "正文A", 1, "req-x").andExpect(status().isServiceUnavailable());
        edit(author, id, "别处又保存的正文B");
        jdbc.update("update sensitive_rules set enabled=true");
        assertEquals("VERSION_CONFLICT", body(publish(author, id, "重试冲突", "摘要", "正文A", 1, "req-x").andExpect(status().isConflict())).path("code").asText());
        assertEquals(0, count("select count(*) from content_posts where author_id=?", author.id()), "不发布陈旧稿");
    }

    @Test void publishValidatesRequiredFieldsLimitsAndRequestIdWithoutTouchingTheDraft() throws Exception {
        long id = draft(author, "只有标题", "", "");
        JsonNode r = body(publish(author, id, "只有标题", "", "", 1, "r1").andExpect(status().isBadRequest()));
        assertEquals("VALIDATION", r.path("code").asText()); assertTrue(r.path("fields").toString().contains("summary") && r.path("fields").toString().contains("body"));
        assertEquals(1, view(author, id).path("version").asInt());
        body(publish(author, id, "😀".repeat(121), "s", "b", 1, "r2").andExpect(status().isBadRequest()));
        call(post("/api/me/articles/" + id + "/publish"), author, Map.of("title", "t", "summary", "s", "body", "b", "expectedVersion", 1)).andExpect(status().isBadRequest());
        assertEquals(0, count("select count(*) from content_posts where author_id=?", author.id()));
    }

    // ------------------------------------------------------------------ CT-A14/A15：删除
    @Test void deletingAnUnpublishedDraftRemovesEveryCopyAndLeavesOnlyBodyFreeAudit() throws Exception {
        String marker = "唯一标记" + UUID.randomUUID().toString().substring(0, 8);
        long id = draft(author, marker, "摘要", "正文含 midword " + marker);
        publishNow(author, id, 200); // 送审，产生状态/提交/流转/回执
        assertEquals(1, count("select count(*) from review_submissions where snapshot_json like ?", "%" + marker + "%"));
        assertEquals("PENDING_SUBMISSION", body(call(delete("/api/me/articles/" + id), author, null).andExpect(status().isConflict())).path("reason").asText());
        call(post("/api/me/articles/" + id + "/cancel"), author, Map.of()).andExpect(status().isOk());

        call(delete("/api/me/articles/" + id), other, null).andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/articles/" + id).session(author.session())).andExpect(status().isForbidden());
        call(delete("/api/me/articles/" + id), null, null).andExpect(status().isUnauthorized());
        assertEquals(1, count("select count(*) from article_drafts where id=?", id));
        call(delete("/api/me/articles/" + id), author, null).andExpect(status().isOk());
        fetch(author, "/api/me/articles/" + id).andExpect(status().isNotFound());
        call(delete("/api/me/articles/" + id), author, null).andExpect(status().isNotFound());
        assertEquals(0, count("select count(*) from article_drafts where id=?", id));
        assertEquals(0, count("select count(*) from review_content_state where content_key=?", "ARTICLE#" + id));
        assertEquals(0, count("select count(*) from review_submissions where snapshot_json like ?", "%" + marker + "%"), "库内不再有该稿正文");
        assertEquals(0, count("select count(*) from review_flows where author_id=?", author.id()));
        assertEquals(0, count("select count(*) from review_flow_events"));
        assertEquals(0, count("select count(*) from review_receipts"));
        assertEquals(1, count("select count(*) from review_audit_log where type='DELETE_DRAFT' and content_type='DRAFT' and content_id=?", id));
        assertEquals(0, count("select count(*) from review_audit_log where coalesce(note,'') like ? or coalesce(result_message,'') like ?", "%" + marker + "%", "%" + marker + "%"));
        assertEquals(0, count("select count(*) from content_posts where author_id=?", author.id()));
    }

    @Test void publishedOrWithdrawnArticlesCannotBeDeleted() throws Exception {
        long id = draft(author, "公开过", "摘要", "正文");
        publishNow(author, id, 200);
        assertEquals("PUBLISHED_BEFORE", body(call(delete("/api/me/articles/" + id), author, null).andExpect(status().isConflict())).path("reason").asText());
        call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isOk());
        assertEquals("PUBLISHED_BEFORE", body(call(delete("/api/me/articles/" + id), author, null).andExpect(status().isConflict())).path("reason").asText(), "撤回后仍保留私人稿，不可删");
        assertEquals(1, count("select count(*) from article_drafts where id=?", id));
    }

    // ------------------------------------------------------------------ CT-A10：权限分工与归属
    @Test void revokedAuthorsCanManageButNotPublishAndOthersGetNotFound() throws Exception {
        long id = draft(author, "权限", "摘要", "正文");
        publishNow(author, id, 200);
        edit(author, id, "更新含 midword"); publishNow(author, id, 200);
        long other1 = draft(other, "别人的", "摘要", "正文");
        call(post("/api/me/articles/" + id + "/cancel"), other, Map.of()).andExpect(status().isNotFound());
        call(post("/api/me/articles/" + id + "/withdraw"), other, Map.of("confirm", true)).andExpect(status().isNotFound());
        call(delete("/api/me/articles/" + id), other, null).andExpect(status().isNotFound());
        publish(other, id, "x", "y", "z", 1, "o1").andExpect(status().isNotFound());
        call(post("/api/me/articles/" + id + "/cancel"), null, Map.of()).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/articles/" + id + "/cancel").session(author.session()).contentType("application/json").content("{}")).andExpect(status().isForbidden());

        t.setRole(author.id(), "RESTRICTED"); // 撤销“发布文章”权限
        JsonNode v = view(author, id);
        publish(author, id, v.path("title").asText(), v.path("summary").asText(), v.path("body").asText(), v.path("version").asLong(), "r-revoked").andExpect(status().isForbidden());
        call(post("/api/me/articles/" + id + "/cancel"), author, Map.of()).andExpect(status().isOk());
        call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isOk());
        assertEquals("WITHDRAWN", view(author, id).path("status").path("publicState").asText());
        assertNotEquals(other1, id);
    }

    // ------------------------------------------------------------------ 点赞原子更新
    @Test void likesOnlyCountOnVisiblePostsAndNeverResurrectModerationState() throws Exception {
        long id = draft(author, "点赞", "摘要", "正文");
        publishNow(author, id, 200); long postId = postOf(id);
        body(call(post("/api/posts/" + postId + "/like"), other, Map.of()).andExpect(status().isOk()));
        manage(postId, "TAKE_DOWN", "VISIBLE", 200);
        call(post("/api/posts/" + postId + "/like"), other, Map.of()).andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject("select likes from content_posts where id=?", Integer.class, postId));
        assertEquals("TAKEN_DOWN", moderation(postId));
        call(post("/api/posts/999999999/like"), other, Map.of()).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ CT-A11：两线程（H2）
    @Test void twoConcurrentPublishesOfTheSameDraftNeverCreateTwoPosts() throws Exception {
        long id = draft(author, "并发发布", "摘要", "正文");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (String text : List.of("正文A", "正文B"))
                jobs.add(() -> publish(author, id, "并发发布", "摘要", text, 1, "race-" + text).andReturn().getResponse().getStatus());
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(jobs)) statuses.add(f.get());
            assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "只有一个请求持有版本并成功: " + statuses);
            assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 409), statuses.toString());
        } finally { pool.shutdownNow(); }
        assertEquals(1, count("select count(*) from content_posts where author_id=?", author.id()));
        assertEquals(1, count("select count(*) from article_drafts where id=? and post_id is not null", id));
    }

    /** 内容相同则版本不变：两个不同 requestId 并发发布相同内容都可成功，但仍只有一条公开行。 */
    @Test void twoConcurrentPublishesOfIdenticalContentStillYieldASinglePost() throws Exception {
        long id = draft(author, "并发相同", "摘要", "正文");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (String rid : List.of("same-a", "same-b"))
                jobs.add(() -> publish(author, id, "并发相同", "摘要", "正文", 1, rid).andReturn().getResponse().getStatus());
            for (Future<Integer> f : pool.invokeAll(jobs)) assertEquals(200, f.get());
        } finally { pool.shutdownNow(); }
        assertEquals(1, count("select count(*) from content_posts where author_id=?", author.id()));
        assertEquals(1, view(author, id).path("version").asInt());
    }

    // ------------------------------------------------------------------ CT-A09/A10：停用账号一律401；恢复账号不解除人工下架
    @Test void aDisabledAuthorIsRejectedEverywhereAndRecoveringTheAccountDoesNotLiftATakedown() throws Exception {
        long id = draft(author, "停用测试", "摘要", "正文");
        assertEquals("PUBLISHED", publishNow(author, id, 200).path("outcome").asText());
        long postId = postOf(id);
        manage(postId, "TAKE_DOWN", "VISIBLE", 200);
        assertEquals("TAKEN_DOWN", moderation(postId));

        JsonNode users = body(mvc.perform(t.read(get("/api/admin/users").param("q", author.email()), owner.session())).andExpect(status().isOk()));
        long version = users.path("items").get(0).path("statusVersion").asLong();
        mvc.perform(t.write(patch("/api/admin/users/" + author.id() + "/status"), owner.session(), Map.of("status", "DISABLED", "expectedVersion", version, "note", "测试停用"))).andExpect(status().isOk());
        fetch(author, "/api/me/articles").andExpect(status().isUnauthorized());
        fetch(author, "/api/me/articles/" + id).andExpect(status().isUnauthorized());
        call(post("/api/me/articles"), author, Map.of("title", "停用后新建")).andExpect(status().isUnauthorized());
        call(post("/api/me/articles/" + id + "/withdraw"), author, Map.of("confirm", true)).andExpect(status().isUnauthorized());
        call(delete("/api/me/articles/" + id), author, null).andExpect(status().isUnauthorized());

        mvc.perform(t.write(patch("/api/admin/users/" + author.id() + "/status"), owner.session(), Map.of("status", "ACTIVE", "expectedVersion", version + 1, "note", "测试恢复"))).andExpect(status().isOk());
        assertEquals("TAKEN_DOWN", moderation(postId), "账号恢复不能解除人工下架");
        fetch(null, "/api/posts/" + postId).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ CT-A07：被维持不通过的复核任务不可再取消
    @Test void anUpheldRecheckTaskIsClosedAndCancellingItStillFails() throws Exception {
        long id = draft(author, "高敏感维持", "摘要", "这里有 highword");
        assertEquals("AUTO_REJECTED", publishNow(author, id, 200).path("outcome").asText());
        long task = pendingTask();
        long generation = jdbc.queryForObject("select generation from review_submissions where id=?", Long.class, task);
        call(post("/api/review/tasks/" + task), owner, Map.of("action", "UPHOLD", "expectedGeneration", generation, "note", "维持不通过")).andExpect(status().isOk());
        assertEquals(0, count("select count(*) from review_submissions where id=? and status='PENDING'", task));
        int status = call(post("/api/me/articles/" + id + "/cancel"), author, Map.of()).andReturn().getResponse().getStatus();
        assertEquals(409, status, "任务已结束，取消送审必须被拒绝");
        assertEquals("REJECTED", view(author, id).path("status").path("work").asText());
        assertTrue(view(author, id).path("canDelete").asBoolean(), "复核有结论后、从未公开的草稿可删（D05①延伸）");
    }

    /** CT-A15：删除与发布同时到达同一份从未公开的草稿——要么删掉（发布404），要么发布成功（删除409），绝不留下“有公开行却没有草稿”。 */
    @Test void deleteRacingWithPublishNeverLeavesAnOrphanedPublicPost() throws Exception {
        for (int round = 0; round < 3; round++) {
            long id = draft(author, "删除竞态" + round, "摘要", "正文");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Integer> statuses = new ArrayList<>();
            try {
                List<Callable<Integer>> jobs = List.of(
                        () -> publish(author, id, "删除竞态", "摘要", "正文", 1, "del-race-" + id).andReturn().getResponse().getStatus(),
                        () -> call(delete("/api/me/articles/" + id), author, null).andReturn().getResponse().getStatus());
                for (Future<Integer> f : pool.invokeAll(jobs)) statuses.add(f.get());
            } finally { pool.shutdownNow(); }
            int published = statuses.get(0), deleted = statuses.get(1);
            boolean draftExists = count("select count(*) from article_drafts where id=?", id) == 1;
            if (draftExists) { // 发布先到：草稿保留并已公开，删除被拒绝
                assertEquals(200, published, statuses.toString()); assertEquals(409, deleted, statuses.toString());
                assertEquals(1, count("select count(*) from article_drafts where id=? and post_id is not null", id));
            } else {          // 删除先到：公开行不存在，发布被拒绝
                assertEquals(200, deleted, statuses.toString()); assertTrue(published == 404 || published == 409, statuses.toString());
            }
            assertEquals(0, count("select count(*) from content_posts p where p.author_id=? and p.title like '删除竞态%' and not exists (select 1 from article_drafts d where d.post_id=p.id)", author.id()), "不得留下无草稿的公开行");
            jdbc.update("delete from article_drafts where author_id=?", author.id());
            jdbc.update("delete from content_posts where author_id=?", author.id());
        }
    }
}
