package com.aitome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 契约 §10 + §13.5：RV-A01–A12、A14、A16–A22（后端行为；需规修订6 三级统一流程）。 */
@SpringBootTest @AutoConfigureMockMvc
class ContentReviewTests {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    AccessTestSupport t;
    AccessTestSupport.Account owner, reviewer, author, plain;

    /** 共享库（尤其是复制的旧 PostgreSQL 库）里可能残留历史运行发布的同名文章，先清理，避免按标题计数的断言被污染。 */
    void cleanPosts() {
        jdbc.update("delete from comments where post_id in (select id from content_posts where title like '标题k-%')");
        jdbc.update("delete from content_posts where title like '标题k-%'");
    }

    @BeforeEach void setUp() throws Exception {
        TestReviewConfig.reset(jdbc);
        cleanPosts();
        t = new AccessTestSupport(mvc, json, jdbc);
        owner = t.register("rvowner"); t.setRole(owner.id(), "OWNER");
        reviewer = t.withRole("rvadmin", "ADMIN");
        jdbc.update("insert into role_permissions(role_id,permission) select id,'content.review' from roles where code='ADMIN' and not exists (select 1 from role_permissions x where x.role_id=roles.id and x.permission='content.review')");
        author = t.register("rvauthor");
        plain = t.register("rvplain");
        addRule("lowword", "LOW", null, true);
        addRule("midword", "MEDIUM", null, true);
        addRule("highword", "HIGH", null, true);
    }

    @AfterEach void tearDown() {
        jdbc.update("delete from role_permissions where permission='content.review' and role_id=(select id from roles where code='ADMIN')");
        jdbc.update("update content_posts set moderation_state='VISIBLE' where moderation_state<>'VISIBLE'");
        jdbc.update("update comments set moderation_state='VISIBLE' where moderation_state<>'VISIBLE'");
        jdbc.update("update community_collections set moderation_state='VISIBLE' where moderation_state<>'VISIBLE'");
        TestReviewConfig.reset(jdbc);
        cleanPosts();
    }

    // ---------------------------------------------------------------- helpers
    void addRule(String phrase, String level, String tag, boolean enabled) {
        jdbc.update("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,level,tag,version,created_at,updated_at) values(?,?,?,false,?,?,?,1,current_timestamp,current_timestamp)", phrase, "", enabled, phrase, level, tag);
    }

    JsonNode body(ResultActions r) throws Exception {
        String text = r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }

    JsonNode jpost(MockHttpSession s, String path, Object payload, int expected) throws Exception { return body(mvc.perform(t.write(post(path), s, payload)).andExpect(status().is(expected))); }
    JsonNode jget(MockHttpSession s, String path, int expected) throws Exception { return body(mvc.perform(t.read(get(path), s)).andExpect(status().is(expected))); }
    int statusOf(MockHttpSession s, String path, Object payload) throws Exception { return mvc.perform(t.write(post(path), s, payload)).andReturn().getResponse().getStatus(); }

    JsonNode article(MockHttpSession s, String key, String requestId, String text, int expected) throws Exception {
        return jpost(s, "/api/posts", articleBody(key, requestId, text), expected);
    }

    Map<String, Object> articleBody(String key, String requestId, String text) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "ARTICLE"); payload.put("title", "标题" + key); payload.put("summary", "摘要"); payload.put("body", text); payload.put("draftKey", key); payload.put("requestId", requestId);
        return payload;
    }

    long pendingTask() { return jdbc.queryForObject("select max(id) from review_submissions where status='PENDING'", Long.class); }
    long generation(long taskId) { return jdbc.queryForObject("select generation from review_submissions where id=?", Long.class, taskId); }

    JsonNode decide(MockHttpSession s, long task, String action, String note, int expected) throws Exception {
        return jpost(s, "/api/review/tasks/" + task, Map.of("action", action, "expectedGeneration", generation(task), "note", note), expected);
    }

    int publicPostCount(String title) throws Exception {
        int count = 0;
        for (int page = 0; ; page++) {
            JsonNode list = jget(null, "/api/posts?size=50&page=" + page, 200);
            for (JsonNode n : list.path("items")) if (title.equals(n.path("title").asText())) count++;
            if ((page + 1) * 50L >= list.path("total").asLong()) return count;
        }
    }

    List<String> stages(long flowId) { return jdbc.queryForList("select stage from review_flow_events where flow_id=? order by occurred_at,id", String.class, flowId); }
    String flowStatus(long flowId) { return jdbc.queryForObject("select status from review_flows where id=?", String.class, flowId); }
    long flowOf(long taskId) { return jdbc.queryForObject("select flow_id from review_submissions where id=?", Long.class, taskId); }

    // ---------------------------------------------------------------- RV-A17 / A18：三级判定
    @Test void cleanArticleAndCommentPublishDirectlyWithOneFlow() throws Exception {
        JsonNode ok = article(author.session(), "k-ok", "r1", "干净内容", 201);
        assertEquals("标题k-ok", ok.path("title").asText());
        assertEquals(1, publicPostCount("标题k-ok"));
        long postId = ok.path("id").asLong();
        jpost(plain.session(), "/api/posts/" + postId + "/comments", Map.of("body", "好文章", "draftKey", "c1", "requestId", "x1"), 201);
        assertEquals(1, jget(null, "/api/posts/" + postId + "/comments", 200).path("total").asInt());
        long flow = jdbc.queryForObject("select max(id) from review_flows where status='PUBLISHED'", Long.class);
        assertEquals(List.of("SUBMIT", "CHECK", "ROUTE", "RESULT"), stages(flow));
    }

    @Test void lowSensitivityPublishesAtOnceAndLeavesATraceThatReviewersCanHandle() throws Exception {
        JsonNode result = article(author.session(), "k-low", "l1", "这里有 lowword 一词", 201);
        assertEquals(1, publicPostCount("标题k-low"));
        assertEquals(0, jdbc.queryForObject("select count(*) from review_submissions", Integer.class), "低敏感不生成任务");
        JsonNode noted = jget(reviewer.session(), "/api/review/noted", 200);
        assertEquals(1, noted.path("total").asInt());
        JsonNode item = noted.path("items").get(0);
        assertEquals("lowword", item.path("words").get(0).asText());
        assertTrue(item.path("published").asBoolean());
        assertFalse(item.path("reviewed").asBoolean());
        jpost(reviewer.session(), "/api/review/noted/" + item.path("id").asLong(), Map.of("action", "REVIEWED"), 200);
        assertTrue(jget(reviewer.session(), "/api/review/noted", 200).path("items").get(0).path("reviewed").asBoolean());
        jget(plain.session(), "/api/review/noted", 403);
        long postId = result.path("id").asLong();
        jpost(reviewer.session(), "/api/review/publications/ARTICLE/" + postId, Map.of("action", "TAKE_DOWN", "expectedModeration", "VISIBLE", "note", "复查后下架"), 200);
        assertFalse(jget(reviewer.session(), "/api/review/noted", 200).path("items").get(0).path("published").asBoolean());
    }

    @Test void mediumSensitivityGoesToReviewOnTheFirstHitWithIdempotentRetryAndSupersede() throws Exception {
        JsonNode first = article(author.session(), "k-mid", "m1", "含 midword", 202);
        assertEquals("PENDING", first.path("outcome").asText());
        assertEquals(0, publicPostCount("标题k-mid"));
        assertEquals(first.path("flowId").asLong(), article(author.session(), "k-mid", "m1", "含 midword", 202).path("flowId").asLong(), "同 requestId 重放不重复生成流转");
        assertEquals(1, jdbc.queryForObject("select count(*) from review_submissions where status='PENDING'", Integer.class));
        long task = pendingTask();
        assertEquals("REVIEW", jdbc.queryForObject("select kind from review_submissions where id=?", String.class, task));
        assertEquals(List.of("SUBMIT", "CHECK", "ROUTE"), stages(first.path("flowId").asLong()));
        assertEquals("IN_REVIEW", flowStatus(first.path("flowId").asLong()));
        JsonNode second = article(author.session(), "k-mid", "m2", "含 midword 改一版", 202);
        assertEquals("INVALID", jdbc.queryForObject("select status from review_submissions where id=?", String.class, task));
        assertEquals("INVALID", flowStatus(first.path("flowId").asLong()));
        assertNotEquals(first.path("flowId").asLong(), second.path("flowId").asLong());
    }

    @Test void highSensitivityIsAutomaticallyRejectedWithARecheckTaskAndTheAuthorNeverSeesTheWords() throws Exception {
        JsonNode result = article(author.session(), "k-high", "h1", "这是 highword 内容", 202);
        assertEquals("AUTO_REJECTED", result.path("outcome").asText());
        assertFalse(result.toString().contains("highword"), "作者响应不含命中词");
        assertEquals(0, publicPostCount("标题k-high"));
        long flowId = result.path("flowId").asLong();
        assertEquals("AUTO_REJECTED", flowStatus(flowId));
        assertEquals(List.of("SUBMIT", "CHECK", "ROUTE", "RESULT"), stages(flowId));
        long task = pendingTask();
        assertEquals("RECHECK", jdbc.queryForObject("select kind from review_submissions where id=?", String.class, task));
        JsonNode mine = jget(author.session(), "/api/review/mine", 200);
        assertFalse(mine.toString().contains("highword"));
        for (JsonNode e : mine.path("items").get(0).path("events")) assertEquals("", e.path("detail").asText());
        JsonNode detail = jget(owner.session(), "/api/review/tasks/" + task, 200);
        assertEquals("highword", detail.path("matches").get(0).asText());
        assertTrue(detail.toString().contains("命中：highword"));
        assertEquals("RECHECK", detail.path("kind").asText());
        assertEquals("HIGH", detail.path("level").asText());
    }

    @Test void highestLevelWinsAndVariantsCannotBypass() throws Exception {
        assertEquals("AUTO_REJECTED", article(author.session(), "k-both", "b1", "midword 和 h.i.g.h w-o-r-d", 202).path("outcome").asText());
        assertEquals("PENDING", article(author.session(), "k-var", "v1", "M I D W O R D", 202).path("outcome").asText());
        article(author.session(), "k-lowmid", "v2", "lowword 和 midword", 202);
        assertEquals("MEDIUM", jdbc.queryForObject("select level from review_submissions where id=?", String.class, pendingTask()));
    }

    // ---------------------------------------------------------------- 复核与审批
    @Test void recheckActionsAreKindSpecificAndRequireReasonsBothWays() throws Exception {
        article(author.session(), "k-rc", "r1", "highword", 202);
        long task = pendingTask();
        decide(reviewer.session(), task, "APPROVE", "x", 400);
        decide(reviewer.session(), task, "UPHOLD", "  ", 400);
        decide(reviewer.session(), task, "OVERTURN", "", 400);
        decide(reviewer.session(), task, "OVERTURN", "属于误判，放行", 200);
        assertEquals(1, publicPostCount("标题k-rc"));
        assertEquals("OVERTURNED", jdbc.queryForObject("select status from review_submissions where id=?", String.class, task));
        decide(reviewer.session(), task, "UPHOLD", "重复", 409);
        assertEquals("PUBLISHED", flowStatus(flowOf(task)));
        assertTrue(stages(flowOf(task)).contains("REVIEW"));
    }

    @Test void upholdingKeepsContentPrivate() throws Exception {
        article(author.session(), "k-up", "u1", "highword", 202);
        long task = pendingTask();
        decide(reviewer.session(), task, "UPHOLD", "维持原判", 200);
        assertEquals(0, publicPostCount("标题k-up"));
        assertEquals("UPHELD", flowStatus(flowOf(task)));
        JsonNode mine = jget(author.session(), "/api/review/mine", 200);
        assertTrue(mine.toString().contains("审核员"));
        assertFalse(mine.toString().contains(reviewerName()), "作者看不到处理人姓名");
    }

    @Test void flowsAreAvailablePerContentForTheAuthorOnly() throws Exception {
        article(author.session(), "k-pc", "pc1", "highword", 202);
        JsonNode pending = jget(author.session(), "/api/review/mine/unpublished?type=ARTICLE", 200);
        assertEquals(1, pending.path("items").size());
        assertEquals("AUTO_REJECTED", pending.path("items").get(0).path("status").asText());
        assertFalse(pending.toString().contains("highword"), "作者响应不含高敏感命中词");
        assertEquals(0, jget(plain.session(), "/api/review/mine/unpublished?type=ARTICLE", 200).path("items").size());
        long task = pendingTask();
        decide(reviewer.session(), task, "OVERTURN", "属于误判，放行", 200);
        assertEquals(0, jget(author.session(), "/api/review/mine/unpublished?type=ARTICLE", 200).path("items").size());
        long postId = jdbc.queryForObject("select id from content_posts where title=?", Long.class, "标题k-pc");
        JsonNode byContent = jget(author.session(), "/api/review/mine/content?type=ARTICLE&ids=" + postId + ",999999", 200);
        assertEquals(1, byContent.path("items").path(String.valueOf(postId)).size());
        assertTrue(byContent.path("items").path(String.valueOf(postId)).get(0).path("events").size() >= 4);
        assertTrue(jget(plain.session(), "/api/review/mine/content?type=ARTICLE&ids=" + postId, 200).path("items").isEmpty(), "别人的内容流转不可见");
        jget(author.session(), "/api/review/mine/content?type=BOGUS&ids=1", 400);
    }

    String reviewerName() { return jdbc.queryForObject("select display_name from user_accounts where id=?", String.class, reviewer.id()); }

    @Test void approvalRulesSelfReviewStaleAndReasons() throws Exception {
        article(author.session(), "k-r", "r1", "midword", 202);
        long task = pendingTask();
        jpost(plain.session(), "/api/review/tasks/" + task, Map.of("action", "APPROVE", "expectedGeneration", 1, "note", ""), 403);
        jpost(null, "/api/review/tasks/" + task, Map.of("action", "APPROVE"), 401);
        decide(reviewer.session(), task, "REJECT", "", 400);
        decide(reviewer.session(), task, "REJECT", "含不当内容", 200);
        assertEquals(0, publicPostCount("标题k-r"));
        article(author.session(), "k-r", "r9", "midword", 202);
        long second = pendingTask();
        long staleGeneration = generation(second);
        decide(reviewer.session(), second, "APPROVE", "", 200);
        assertEquals(1, publicPostCount("标题k-r"));
        jpost(reviewer.session(), "/api/review/tasks/" + second, Map.of("action", "APPROVE", "expectedGeneration", staleGeneration, "note", ""), 409);
        assertEquals(1, publicPostCount("标题k-r"));
    }

    @Test void authorWithReviewPermissionCannotReviewOwnWork() throws Exception {
        article(owner.session(), "k-self", "s1", "midword", 202);
        long task = pendingTask();
        JsonNode denied = jpost(owner.session(), "/api/review/tasks/" + task, Map.of("action", "APPROVE", "expectedGeneration", generation(task), "note", ""), 403);
        assertEquals("SELF_REVIEW", denied.path("code").asText());
        assertEquals("PENDING", jdbc.queryForObject("select status from review_submissions where id=?", String.class, task));
        assertEquals(1, jdbc.queryForObject("select count(*) from review_audit_log where outcome='REJECTED'", Integer.class));
    }

    @Test void revokedReviewerAndDisabledAuthorCannotFinishOldTasks() throws Exception {
        article(author.session(), "k-rv", "v1", "midword", 202);
        long task = pendingTask();
        jdbc.update("delete from role_permissions where permission='content.review' and role_id=(select id from roles where code='ADMIN')");
        decide(reviewer.session(), task, "APPROVE", "", 403);
        jdbc.update("insert into role_permissions(role_id,permission) select id,'content.review' from roles where code='ADMIN'");
        jdbc.update("update user_accounts set account_status='DISABLED' where id=?", author.id());
        jpost(reviewer.session(), "/api/review/tasks/" + task, Map.of("action", "APPROVE", "expectedGeneration", generation(task), "note", ""), 409);
        assertEquals(0, publicPostCount("标题k-rv"));
        jdbc.update("update user_accounts set account_status='ACTIVE' where id=?", author.id());
    }

    @Test void concurrentApprovalsPublishExactlyOnce() throws Exception {
        article(author.session(), "k-cc", "c1", "midword", 202);
        long task = pendingTask();
        long generation = generation(task);
        var second = t.register("rvowner2");
        t.setRole(second.id(), "OWNER");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> statusOf(reviewer.session(), "/api/review/tasks/" + task, Map.of("action", "APPROVE", "expectedGeneration", generation, "note", "")),
                    () -> statusOf(second.session(), "/api/review/tasks/" + task, Map.of("action", "APPROVE", "expectedGeneration", generation, "note", "")));
            int ok = 0;
            for (Future<Integer> f : pool.invokeAll(calls)) if (f.get() == 200) ok++;
            assertEquals(1, ok);
            assertEquals(1, publicPostCount("标题k-cc"));
        } finally { pool.shutdownNow(); }
    }

    // ---------------------------------------------------------------- 下架 / 恢复
    @Test void takeDownHidesEverywhereAndRestoreNeedsAnotherReviewerReasonAndAcknowledgementWhenMatched() throws Exception {
        long id = article(author.session(), "k-td", "t1", "干净", 201).path("id").asLong();
        jpost(reviewer.session(), "/api/review/publications/ARTICLE/" + id, Map.of("action", "TAKE_DOWN", "expectedModeration", "VISIBLE", "note", ""), 400);
        jpost(reviewer.session(), "/api/review/publications/ARTICLE/" + id, Map.of("action", "TAKE_DOWN", "expectedModeration", "VISIBLE", "note", "违规"), 200);
        jget(null, "/api/posts/" + id, 404);
        assertEquals(0, publicPostCount("标题k-td"));
        article(author.session(), "k-td", "t2", "干净", 409);
        addRule("干净", "MEDIUM", null, true);
        JsonNode matched = jpost(reviewer.session(), "/api/review/publications/ARTICLE/" + id, Map.of("action", "RESTORE", "expectedModeration", "TAKEN_DOWN", "note", "复核"), 409);
        assertEquals("MATCHED", matched.path("code").asText());
        jpost(reviewer.session(), "/api/review/publications/ARTICLE/" + id, Map.of("action", "RESTORE", "expectedModeration", "TAKEN_DOWN", "note", "复核", "acknowledgeMatches", true), 200);
        assertEquals(1, publicPostCount("标题k-td"));
        long mine = article(owner.session(), "k-own", "o1", "普通", 201).path("id").asLong();
        jpost(owner.session(), "/api/review/publications/ARTICLE/" + mine, Map.of("action", "TAKE_DOWN", "expectedModeration", "VISIBLE", "note", "测试"), 200);
        assertEquals("SELF_REVIEW", jpost(owner.session(), "/api/review/publications/ARTICLE/" + mine, Map.of("action", "RESTORE", "expectedModeration", "TAKEN_DOWN", "note", "x"), 403).path("code").asText());
    }

    // ---------------------------------------------------------------- 专题与文章一致
    @Test void collectionsFollowTheSamePipelineAndFirstPublicationNoLongerNeedsApproval() throws Exception {
        long clean = collection(author.session(), "正常专题", "正常内容");
        assertEquals("PUBLISHED", publishCollection(author.session(), clean, "p1", 200).path("status").asText());
        jget(null, "/api/collections/" + clean + "/outline", 200);

        long mid = collection(author.session(), "含 midword 的专题", "内容");
        JsonNode pending = publishCollection(author.session(), mid, "p2", 202);
        assertEquals("PENDING", pending.path("outcome").asText());
        jget(null, "/api/collections/" + mid + "/outline", 404);
        long task = pendingTask();
        assertEquals("REVIEW", jdbc.queryForObject("select kind from review_submissions where id=?", String.class, task));
        decide(reviewer.session(), task, "APPROVE", "", 200);
        jget(null, "/api/collections/" + mid + "/outline", 200);

        long high = collection(author.session(), "高敏专题", "含 highword");
        assertEquals("AUTO_REJECTED", publishCollection(author.session(), high, "p3", 202).path("outcome").asText());
        jget(null, "/api/collections/" + high + "/outline", 404);
        decide(reviewer.session(), pendingTask(), "OVERTURN", "误判放行", 200);
        jget(null, "/api/collections/" + high + "/outline", 200);
    }

    @Test void collectionUpdatesKeepTheOldVersionWhileReviewedAndArchiveInvalidatesThePendingFlow() throws Exception {
        long id = collection(author.session(), "更新专题", "第一版");
        publishCollection(author.session(), id, "u1", 200);
        JsonNode draft = jget(author.session(), "/api/me/collections/" + id + "/draft", 200);
        save(author.session(), id, draft, "midword 第二版");
        assertEquals("PENDING", publishCollection(author.session(), id, "u2", 202).path("outcome").asText());
        assertTrue(jget(null, "/api/collections/" + id + "/outline", 200).toString().contains("第一版"), "旧发布版继续可见");
        long task = pendingTask();
        long flow = flowOf(task);
        jpost(author.session(), "/api/me/collections/" + id + "/archive", Map.of("revision", jget(author.session(), "/api/me/collections/" + id + "/draft", 200).path("revision").asLong()), 200);
        assertEquals("INVALID", jdbc.queryForObject("select status from review_submissions where id=?", String.class, task));
        assertEquals("INVALID", flowStatus(flow));
    }

    // ---------------------------------------------------------------- 规则、等级、批量、导入
    @Test void rulesAreMaintainedOnlyByTheCurrentOwnerWithLevelsAndTags() throws Exception {
        jpost(reviewer.session(), "/api/review/rules", Map.of("phrase", "x1", "level", "HIGH"), 403);
        jget(reviewer.session(), "/api/review/rules", 403);
        JsonNode created = jpost(owner.session(), "/api/review/rules", Map.of("phrase", " New Rule ", "note", "备注", "enabled", true, "level", "HIGH", "tag", "测试"), 200);
        assertEquals("New Rule", created.path("phrase").asText());
        assertEquals("HIGH", created.path("level").asText());
        jpost(owner.session(), "/api/review/rules", Map.of("phrase", "n.e.w r-u-l-e"), 400);
        jpost(owner.session(), "/api/review/rules", Map.of("phrase", "…… ！！"), 400);
        jpost(owner.session(), "/api/review/rules", Map.of("phrase", "z", "level", "SUPER"), 400);
        long id = created.path("id").asLong(); long version = created.path("version").asLong();
        mvc.perform(t.write(put("/api/review/rules/" + id), owner.session(), Map.of("phrase", "Renamed", "enabled", true, "level", "LOW", "tag", "测试", "expectedVersion", version))).andExpect(status().isOk());
        mvc.perform(t.write(put("/api/review/rules/" + id), owner.session(), Map.of("phrase", "Stale", "expectedVersion", version))).andExpect(status().isConflict());
        JsonNode list = body(mvc.perform(get("/api/review/rules").param("level", "LOW").param("tag", "测试").session(owner.session())).andExpect(status().isOk()));
        assertEquals(1, list.path("total").asInt());
        long fresh = list.path("items").get(0).path("version").asLong();
        mvc.perform(t.write(delete("/api/review/rules/" + id), owner.session(), Map.of("expectedVersion", fresh - 1))).andExpect(status().isConflict());
        mvc.perform(t.write(delete("/api/review/rules/" + id), owner.session(), Map.of("expectedVersion", fresh))).andExpect(status().isOk());
        jpost(owner.session(), "/api/review/rules", Map.of("phrase", "Renamed"), 200);
        assertEquals(1, jget(owner.session(), "/api/review/rules", 200).path("levels").path("HIGH").asInt());
    }

    @Test void ruleChangesTakeEffectImmediatelyDespiteTheCompiledMatcherCache() throws Exception {
        article(author.session(), "k-c1", "c1", "含 midword", 202);
        JsonNode rule = jget(owner.session(), "/api/review/rules?q=midword", 200).path("items").get(0);
        mvc.perform(t.write(patch("/api/review/rules/" + rule.path("id").asLong() + "/enabled"), owner.session(), Map.of("enabled", false, "expectedVersion", rule.path("version").asLong()))).andExpect(status().isOk());
        article(author.session(), "k-c2", "c2", "含 midword", 201);
        mvc.perform(t.write(put("/api/review/rules/" + rule.path("id").asLong()), owner.session(), Map.of("phrase", "midword", "enabled", true, "level", "HIGH", "expectedVersion", rule.path("version").asLong() + 1))).andExpect(status().isOk());
        assertEquals("AUTO_REJECTED", article(author.session(), "k-c3", "c3", "含 midword", 202).path("outcome").asText(), "改为高敏感后立即生效");
    }

    @Test void batchToggleByTagAffectsOnlyLaterSubmissionsAndIsOwnerOnly() throws Exception {
        addRule("tagone", "MEDIUM", "批量", false); addRule("tagtwo", "MEDIUM", "批量", false);
        article(author.session(), "k-b1", "b1", "tagone", 201);
        JsonNode result = jpost(owner.session(), "/api/review/rules/batch", Map.of("tag", "批量", "enabled", true), 200);
        assertEquals(2, result.path("count").asInt());
        article(author.session(), "k-b2", "b2", "tagtwo", 202);
        jpost(owner.session(), "/api/review/rules/batch", Map.of("tag", "不存在", "enabled", true), 404);
        jpost(reviewer.session(), "/api/review/rules/batch", Map.of("tag", "批量", "enabled", false), 403);
        JsonNode tags = jget(owner.session(), "/api/review/rules", 200).path("tags");
        assertEquals("批量", tags.get(0).path("tag").asText());
        assertEquals(2, tags.get(0).path("enabled").asInt());
    }

    @Test void noEnabledRulesBlocksPublishingWithoutCreatingAnyFlow() throws Exception {
        jdbc.update("update sensitive_rules set enabled=false");
        JsonNode down = article(author.session(), "q3", "q3", "任何内容", 503);
        assertEquals("DETECTION_UNAVAILABLE", down.path("code").asText());
        assertEquals(0, jdbc.queryForObject("select count(*) from review_flows", Integer.class), "检测不可用不建流转");
        assertEquals(0, jdbc.queryForObject("select count(*) from review_content_state where content_key='ARTICLE:q3'", Integer.class));
    }

    @Test void lexiconImportPreviewAndCommitGroupByTagWithSuspectsExcludedByDefault() throws Exception {
        String politics = "长词语甲\n长词语乙\nab\n这是一个很长很长的词语丙\n";
        String ads = "广告词一\n广告词二\n长词语甲\n";
        var csrf = t.csrf();
        JsonNode preview = body(mvc.perform(multipart("/api/review/rules/import-preview")
                .file(new MockMultipartFile("files", "政治类型.txt", "text/plain", politics.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("files", "广告类型.txt", "text/plain", ads.getBytes(StandardCharsets.UTF_8)))
                .session(owner.session()).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isOk()));
        assertEquals(2, preview.path("groups").size());
        JsonNode political = null;
        for (JsonNode g : preview.path("groups")) if ("政治类型".equals(g.path("tag").asText())) political = g;
        assertNotNull(political);
        assertEquals("HIGH", political.path("level").asText());
        assertTrue(political.path("enabled").asBoolean(), "政治类型建议启用");
        assertEquals(1, political.path("suspect").asInt());
        assertEquals(0, jdbc.queryForObject("select count(*) from sensitive_rules where tag is not null", Integer.class), "预览不写库");

        String config = json.writeValueAsString(Map.of("groups", List.of(Map.of("tag", "政治类型", "level", "HIGH", "enabled", true), Map.of("tag", "广告类型", "level", "LOW", "enabled", false)), "includeSuspect", false));
        JsonNode committed = body(importRequest(owner.session(), config, politics, ads).andExpect(status().isOk()));
        assertEquals(5, committed.path("added").asInt(), "去重后：甲/乙/丙/广告一/广告二；ab 被排除");
        assertEquals(1, committed.path("skippedSuspect").asInt());
        assertEquals("政治类型", jdbc.queryForObject("select tag from sensitive_rules where phrase='长词语甲'", String.class), "同词多类别保留最高等级的类别");
        assertEquals(0, jdbc.queryForObject("select count(*) from sensitive_rules where phrase='广告词一' and enabled=true", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from sensitive_rules where phrase='长词语甲' and enabled=true and level='HIGH'", Integer.class));
        assertEquals(0, body(importRequest(owner.session(), config, politics, ads).andExpect(status().isOk())).path("added").asInt(), "重复导入全部跳过");
        assertEquals("AUTO_REJECTED", article(author.session(), "k-imp", "i1", "出现了长词语乙", 202).path("outcome").asText());
        importRequest(reviewer.session(), config, politics, ads).andExpect(status().isForbidden());
        importRequest(owner.session(), "{bad json", politics).andExpect(status().isBadRequest());
    }

    ResultActions importRequest(MockHttpSession s, String config, String... files) throws Exception {
        var builder = multipart("/api/review/rules/import");
        String[] names = {"政治类型.txt", "广告类型.txt"};
        for (int i = 0; i < files.length; i++) builder.file(new MockMultipartFile("files", names[i], "text/plain", files[i].getBytes(StandardCharsets.UTF_8)));
        var csrf = t.csrf();
        return mvc.perform(builder.param("config", config).session(s).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()));
    }

    @Test void lexiconImportRejectsInvalidEncodingOversizedLineCountsAndNonOwners() throws Exception {
        var csrf = t.csrf();
        mvc.perform(multipart("/api/review/rules/import-preview").file(new MockMultipartFile("files", "坏编码.txt", "text/plain", new byte[]{(byte) 0xFF, (byte) 0xFE, 0x41, 0x0A}))
                .session(owner.session()).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isBadRequest());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20_001; i++) many.append("词语").append(i).append('\n');
        mvc.perform(multipart("/api/review/rules/import-preview").file(new MockMultipartFile("files", "太多.txt", "text/plain", many.toString().getBytes(StandardCharsets.UTF_8)))
                .session(owner.session()).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/review/rules/import-preview").file(new MockMultipartFile("files", "x.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8)))
                .session(reviewer.session()).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isForbidden());
    }

    @Test void queueSupportsFiltersPaginationAndTheAuthorSeesOnlyOwnFlows() throws Exception {
        article(author.session(), "k-q1", "q1", "midword", 202);
        article(author.session(), "k-q2", "q2", "highword", 202);
        JsonNode queue = jget(reviewer.session(), "/api/review/queue?level=MEDIUM&type=ARTICLE&q=" + java.net.URLEncoder.encode("rvauthor", StandardCharsets.UTF_8), 200);
        assertEquals(1, queue.path("total").asInt());
        assertEquals(1, queue.path("medium").asInt()); assertEquals(1, queue.path("high").asInt());
        assertEquals(0, jget(reviewer.session(), "/api/review/queue?type=COMMENT", 200).path("total").asInt());
        assertEquals(1, jget(reviewer.session(), "/api/review/queue?level=HIGH", 200).path("total").asInt());
        assertEquals(2, jget(author.session(), "/api/review/mine", 200).path("total").asInt());
        assertEquals(0, jget(plain.session(), "/api/review/mine", 200).path("total").asInt());
        assertEquals(2, jget(author.session(), "/api/review/mine?type=ARTICLE", 200).path("total").asInt());
        assertEquals(0, jget(author.session(), "/api/review/mine?type=COLLECTION", 200).path("total").asInt());
        jget(author.session(), "/api/review/mine?type=BOGUS", 400);
        jget(plain.session(), "/api/review/queue", 403);
        long task = jdbc.queryForObject("select max(id) from review_submissions where level='MEDIUM' and status='PENDING'", Long.class);
        decide(reviewer.session(), task, "REJECT", "补充来源", 200);
        assertEquals(1, jget(reviewer.session(), "/api/review/queue", 200).path("doneToday").asInt());
        assertEquals(1, jget(reviewer.session(), "/api/review/queue?status=DONE", 200).path("total").asInt());
    }

    @Test void newRoleDoesNotGetReviewPermissionByDefault() {
        assertEquals(0, jdbc.queryForObject("select count(*) from role_permissions rp join roles r on r.id=rp.role_id where rp.permission='content.review' and r.system_kind='DEFAULT'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from role_permissions rp join roles r on r.id=rp.role_id where rp.permission='content.review' and r.system_kind='OWNER'", Integer.class));
    }

    // ---------------------------------------------------------------- helpers: collections
    long collection(MockHttpSession s, String name, String text) throws Exception {
        JsonNode created = jpost(s, "/api/me/collections/drafts", Map.of("name", name, "format", "CUSTOM"), 201);
        long id = created.path("id").asLong();
        save(s, id, created, text);
        return id;
    }

    void save(MockHttpSession s, long id, JsonNode draft, String text) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("revision", draft.path("revision").asLong());
        body.put("name", draft.path("name").asText());
        body.put("description", draft.path("description").asText(""));
        body.put("format", draft.path("format").asText());
        body.put("audience", ""); body.put("goal", ""); body.put("visibility", "PUBLIC");
        body.set("sections", json.valueToTree(List.of(Map.of("title", "第一章", "description", "", "entries", List.of(Map.of("kind", "NOTE", "title", "", "annotation", "", "body", text))))));
        mvc.perform(t.write(put("/api/me/collections/" + id + "/draft"), s, json.writeValueAsString(body))).andExpect(status().isOk());
    }

    JsonNode publishCollection(MockHttpSession s, long id, String requestId, int expected) throws Exception {
        long revision = jget(s, "/api/me/collections/" + id + "/draft", 200).path("revision").asLong();
        return jpost(s, "/api/me/collections/" + id + "/publish", Map.of("revision", revision, "requestId", requestId), expected);
    }
}
