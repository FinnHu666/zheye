package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.common.TextLimits;
import com.aitome.review.ReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * FINNS-18 B2：文章草稿的创建、读取、手动保存与列表（契约修订2 §3.0–§3.2、§4）。发布/取消/撤回/删除在 B3。
 * 身份由控制器取自服务端会话；他人草稿一律 404。保存先锁草稿行（锁序第一把），乐观版本。
 */
@Service
public class ArticleDraftService {
    static final int TITLE_MAX = 120, SUMMARY_MAX = 280, BODY_MAX = 20000;
    private static final Set<String> STATES = Set.of("ALL", "DRAFT", "REVIEW", "LIVE", "OFF");

    private final ArticleDraftRepository drafts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ReviewService review;
    private final ArticleDraftWriter writer;

    public ArticleDraftService(ArticleDraftRepository drafts, JdbcTemplate jdbc, ObjectMapper json, ReviewService review, ArticleDraftWriter writer) {
        this.drafts = drafts; this.jdbc = jdbc; this.json = json; this.review = review; this.writer = writer;
    }

    // ------------------------------------------------------------------ 写

    @Transactional
    public Map<String, Object> create(long userId, String title, String summary, String body) {
        checkContent(title, summary, body);
        ArticleDraft saved = drafts.saveAndFlush(new ArticleDraft(userId, title, summary, body));
        return view(saved, true);
    }

    @Transactional
    public Map<String, Object> save(long userId, long id, String title, String summary, String body, Long expectedVersion) {
        ArticleDraft draft = owned(id, userId, true);
        checkContent(title, summary, body);
        if (expectedVersion == null) throw new ApiProblem(HttpStatus.BAD_REQUEST, "缺少草稿版本，请刷新后重试。", "VALIDATION", Map.of("fields", List.of("expectedVersion")));
        if (expectedVersion != draft.getVersion()) throw conflict(draft);
        draft.applyContent(title, summary, body);
        drafts.saveAndFlush(draft);
        return view(draft, true);
    }

    // ------------------------------------------------------------------ 发布/取消/撤回/删除（B3，契约修订2 §3.3–§3.9）

    /**
     * 发布/更新。本方法刻意**不加事务**：①回执回放 ②独立事务保存（提交后返回）③检测与提交事务。检测不可用（503）时草稿已保存、旧公开版不变。
     */
    public Map<String, Object> publish(long userId, String authorName, long id, String title, String summary, String body, Long expectedVersion, String requestId) {
        drafts.findById(id).filter(d -> d.getAuthorId() == userId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "未找到属于当前账号的文章。"));
        TextLimits.require("title", "标题", title, TITLE_MAX);
        TextLimits.require("summary", "摘要", summary, SUMMARY_MAX);
        TextLimits.require("body", "正文", body, BODY_MAX);
        List<String> missing = new ArrayList<>();
        if (TextLimits.blank(title)) missing.add("title");
        if (TextLimits.blank(summary)) missing.add("summary");
        if (TextLimits.blank(body)) missing.add("body");
        if (!missing.isEmpty()) {
            List<String> labels = missing.stream().map(f -> "title".equals(f) ? "标题" : "summary".equals(f) ? "摘要" : "正文").toList();
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "发布前请补全：" + String.join("、", labels) + "。草稿已保留，可以先保存。", "VALIDATION", Map.of("fields", missing));
        }
        if (requestId == null || requestId.isBlank() || TextLimits.length(requestId) > 64) throw new ApiProblem(HttpStatus.BAD_REQUEST, "提交标识缺失或过长，请重试。", "VALIDATION", Map.of("fields", List.of("requestId")));
        if (expectedVersion == null) throw new ApiProblem(HttpStatus.BAD_REQUEST, "缺少草稿版本，请刷新后重试。", "VALIDATION", Map.of("fields", List.of("expectedVersion")));
        String rid = requestId.trim();
        String contentHash = hash(title, summary, body);
        java.util.Optional<ReviewService.SubmitResult> replay = review.articleReceipt(userId, id, rid);
        if (replay.isPresent()) {
            // 同一 requestId 的回放必须带同样的内容：否则是误用（草稿记着最近一次请求的 id 与内容哈希；更早的请求已无法比对，按已存结果回放）
            ArticleDraft current = drafts.findById(id).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "未找到属于当前账号的文章。"));
            if (rid.equals(current.getLastRequestId()) && !contentHash.equals(current.getLastRequestHash()))
                throw new ApiProblem(HttpStatus.CONFLICT, "同一个提交标识不能用于不同的内容，请重新发起发布。", "REQUEST_REUSED", null);
            return result(replay.get(), userId, id);
        }
        long savedVersion = writer.saveForPublish(userId, id, title, summary, body, expectedVersion, rid, contentHash);
        return result(review.submitArticleDraft(userId, authorName, id, rid, savedVersion), userId, id);
    }

    private Map<String, Object> result(ReviewService.SubmitResult r, long userId, long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", r.outcome()); out.put("level", r.level() == null ? "NONE" : r.level()); out.put("message", r.message());
        out.put("flowId", r.flowId()); out.put("taskId", r.taskId()); out.put("replay", r.replay());
        out.put("article", get(userId, id)); // 高敏感：flows 已按作者视角隐藏命中词，matches 恒为空，不在此返回
        return out;
    }

    private static String hash(String title, String summary, String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((title + "\u0000" + summary + "\u0000" + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Transactional
    public Map<String, Object> cancel(long userId, String authorName, long id) {
        ArticleDraft draft = owned(id, userId, true); // 锁序：草稿 → 状态
        review.cancelArticleSubmission(userId, authorName, id);
        return Map.of("message", "已取消本次送审，工作稿和旧公开版保留。", "article", view(draft, true));
    }

    @Transactional
    public Map<String, Object> withdraw(long userId, String authorName, long id, Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "撤回会停止公开并使待处理提交失效，需要二次确认。", "CONFIRM_REQUIRED", null);
        ArticleDraft draft = owned(id, userId, true);
        review.withdrawArticle(userId, authorName, id, draft.getPostId(), draft.getTitle(), draft.getVersion());
        return Map.of("message", "已撤回，公开入口不再显示；私人稿保留，重新发布会重新检测。", "article", view(draft, true));
    }

    @Transactional
    public Map<String, Object> delete(long userId, String authorName, long id) {
        ArticleDraft draft = owned(id, userId, true);
        if (draft.getPostId() != null) throw new ApiProblem(HttpStatus.CONFLICT, "这篇文章公开过，不能删除；可以撤回公开后保留私人稿。", "NOT_DELETABLE", Map.of("reason", "PUBLISHED_BEFORE"));
        review.deleteArticleTraces(userId, authorName, id);
        drafts.delete(draft);
        drafts.flush();
        return Map.of("message", "私人草稿已删除。");
    }

    // ------------------------------------------------------------------ 读

    @Transactional(readOnly = true)
    public Map<String, Object> get(long userId, long id) { return view(owned(id, userId, false), true); }

    @Transactional(readOnly = true)
    public Map<String, Object> list(long userId, String state, String q, int page, int size) {
        String filter = state == null || state.isBlank() ? "ALL" : state;
        if (!STATES.contains(filter)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "状态筛选无效。", "VALIDATION", Map.of("fields", List.of("state")));
        String needle = q == null ? "" : TextLimits.cut(q.strip(), 80).toLowerCase(Locale.ROOT);
        int safeSize = Math.max(1, Math.min(size, 50)), safePage = Math.max(0, Math.min(page, 10_000));
        List<ArticleDraft> mine = drafts.findByAuthorIdOrderByUpdatedAtDescIdDesc(userId);
        if (!needle.isEmpty()) mine = mine.stream().filter(d -> d.getTitle().toLowerCase(Locale.ROOT).contains(needle) || d.getSummary().toLowerCase(Locale.ROOT).contains(needle)).toList();
        Map<Long, Info> infos = load(userId, mine);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ArticleDraft d : mine) {
            Map<String, Object> row = row(d, infos.get(d.getId()));
            if (matches(filter, (String) ((Map<?, ?>) row.get("status")).get("publicState"), (String) ((Map<?, ?>) row.get("status")).get("work"))) rows.add(row);
        }
        int from = Math.min(rows.size(), safePage * safeSize), to = Math.min(rows.size(), from + safeSize);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows.subList(from, to)); result.put("page", safePage); result.put("size", safeSize);
        result.put("total", rows.size()); result.put("hasMore", to < rows.size());
        return result;
    }

    /** 契约 §14 R9：筛选语义。同一文章可同时属于 LIVE 与 REVIEW。 */
    static boolean matches(String filter, String publicState, String work) {
        return switch (filter) {
            case "DRAFT" -> "NONE".equals(publicState) && ("PRIVATE".equals(work) || "SAVED".equals(work));
            case "REVIEW" -> "REVIEWING".equals(work) || "RECHECK".equals(work) || "REJECTED".equals(work);
            case "LIVE" -> "LIVE".equals(publicState);
            case "OFF" -> "WITHDRAWN".equals(publicState) || "DOWN".equals(publicState);
            default -> true;
        };
    }

    // ------------------------------------------------------------------ 校验

    private static void checkContent(String title, String summary, String body) {
        TextLimits.require("title", "标题", title, TITLE_MAX);
        TextLimits.require("summary", "摘要", summary, SUMMARY_MAX);
        TextLimits.require("body", "正文", body, BODY_MAX);
        if (TextLimits.blank(title) && TextLimits.blank(summary) && TextLimits.blank(body))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "标题、摘要和正文都为空，不会创建或保存空草稿。", "EMPTY_DRAFT", null);
    }

    private ArticleDraft owned(long id, long userId, boolean lock) {
        Optional<ArticleDraft> found = lock ? drafts.lockById(id) : drafts.findById(id);
        return found.filter(d -> d.getAuthorId() == userId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "未找到属于当前账号的文章。"));
    }

    static ApiProblem conflict(ArticleDraft draft) {
        return new ApiProblem(HttpStatus.CONFLICT, "这份草稿已在别处更新到 v" + draft.getVersion() + "。你的输入已保留，请核对后再保存。", "VERSION_CONFLICT", Map.of("currentVersion", draft.getVersion()));
    }

    // ------------------------------------------------------------------ 视图与状态推导（契约 §4.1）

    private record Post(long id, String moderation, String title, String summary, String body, Instant updatedAt) {}
    private record Task(long id, String kind, String level, String snapshotJson, long version, Instant at) {}
    private static final class Info {
        Post post; Long stateId; Task pending; String latestFlowStatus; Long publishedVersion; boolean hasFlows;
    }

    private Map<Long, Info> load(long userId, List<ArticleDraft> list) {
        Map<Long, Info> infos = new LinkedHashMap<>();
        for (ArticleDraft d : list) infos.put(d.getId(), new Info());
        if (list.isEmpty()) return infos;
        List<Long> postIds = list.stream().map(ArticleDraft::getPostId).filter(Objects::nonNull).toList();
        Map<Long, Post> posts = new HashMap<>();
        if (!postIds.isEmpty())
            jdbc.query("select id,moderation_state,title,summary,body,updated_at from content_posts where id in (" + marks(postIds.size()) + ")", rs -> {
                Timestamp u = rs.getTimestamp(6);
                posts.put(rs.getLong(1), new Post(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), u == null ? null : u.toInstant()));
            }, postIds.toArray());
        Map<Long, Long> stateByDraft = new HashMap<>();
        jdbc.query("select id,content_key from review_content_state where author_id=? and content_type='ARTICLE' and content_key like 'ARTICLE#%'", rs -> {
            try { stateByDraft.put(Long.parseLong(rs.getString(2).substring("ARTICLE#".length())), rs.getLong(1)); } catch (NumberFormatException ignored) { /* 非草稿键，跳过 */ }
        }, userId);
        Map<Long, Long> draftByState = new HashMap<>();
        for (ArticleDraft d : list) {
            Info info = infos.get(d.getId());
            if (d.getPostId() != null) info.post = posts.get(d.getPostId());
            Long stateId = stateByDraft.get(d.getId());
            info.stateId = stateId;
            if (stateId != null) draftByState.put(stateId, d.getId());
        }
        if (!draftByState.isEmpty()) {
            List<Long> stateIds = new ArrayList<>(draftByState.keySet());
            jdbc.query("select r.state_id,r.id,r.kind,r.level,r.snapshot_json,r.snapshot_version,r.created_at from review_submissions r where r.status='PENDING' and r.state_id in (" + marks(stateIds.size()) + ") order by r.id", rs -> {
                Info info = infos.get(draftByState.get(rs.getLong(1)));
                info.pending = new Task(rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getTimestamp(7).toInstant());
            }, stateIds.toArray());
            jdbc.query("select f.state_id,f.status,f.snapshot_version from review_flows f where f.state_id in (" + marks(stateIds.size()) + ") order by f.id", rs -> {
                Info info = infos.get(draftByState.get(rs.getLong(1)));
                info.hasFlows = true; info.latestFlowStatus = rs.getString(2);
                if ("PUBLISHED".equals(rs.getString(2))) info.publishedVersion = rs.getLong(3);
            }, stateIds.toArray());
        }
        return infos;
    }

    private static String marks(int n) { return String.join(",", Collections.nCopies(n, "?")); }

    private Map<String, Object> status(ArticleDraft d, Info info) {
        String publicState = info.post == null ? "NONE" : switch (info.post.moderation()) {
            case "VISIBLE" -> "LIVE"; case "WITHDRAWN" -> "WITHDRAWN"; case "TAKEN_DOWN" -> "DOWN"; default -> "NONE";
        };
        String work;
        if (info.pending != null) work = "RECHECK".equals(info.pending.kind()) ? "RECHECK" : "REVIEWING";
        else if ("WITHDRAWN".equals(publicState)) work = "SAVED"; // D05②：撤回后只显示“已撤回 + 已保存”
        else if ("REJECTED".equals(info.latestFlowStatus) || "UPHELD".equals(info.latestFlowStatus)) work = "REJECTED";
        else if ("LIVE".equals(publicState) && info.post != null && !(d.getTitle().equals(info.post.title()) && d.getSummary().equals(info.post.summary()) && d.getBody().equals(info.post.body()))) work = "MODIFIED";
        else if ("NONE".equals(publicState) && !info.hasFlows) work = "PRIVATE";
        else work = "SAVED";
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("publicState", publicState); status.put("work", work);
        return status;
    }

    private Map<String, Object> row(ArticleDraft d, Info info) {
        Map<String, Object> status = status(d, info);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", d.getId()); row.put("postId", d.getPostId()); row.put("title", d.getTitle()); row.put("summary", d.getSummary());
        row.put("version", d.getVersion()); row.put("status", status); row.put("updatedAt", d.getUpdatedAt());
        row.put("hasPublished", info.post != null); row.put("publicTitle", info.post == null ? null : info.post.title());
        row.put("canCancel", info.pending != null && "REVIEW".equals(info.pending.kind()));
        row.put("canWithdraw", "LIVE".equals(status.get("publicState")));
        row.put("canDelete", d.getPostId() == null && info.pending == null);
        return row;
    }

    private Map<String, Object> view(ArticleDraft d, boolean flows) {
        Info info = load(d.getAuthorId(), List.of(d)).get(d.getId());
        Map<String, Object> view = row(d, info);
        view.remove("hasPublished"); view.remove("publicTitle");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.getId()); out.put("postId", d.getPostId()); out.put("title", d.getTitle()); out.put("summary", d.getSummary()); out.put("body", d.getBody());
        out.put("version", d.getVersion()); out.put("createdAt", d.getCreatedAt()); out.put("updatedAt", d.getUpdatedAt());
        out.put("status", view.get("status"));
        boolean live = "LIVE".equals(((Map<?, ?>) view.get("status")).get("publicState"));
        out.put("published", live && info.post != null ? published(info) : null);
        out.put("submitted", info.pending == null ? null : submitted(info.pending));
        out.put("canCancel", view.get("canCancel")); out.put("canWithdraw", view.get("canWithdraw")); out.put("canDelete", view.get("canDelete"));
        out.put("flows", flows && info.stateId != null ? review.articleFlows(info.stateId, 5) : List.of());
        return out;
    }

    private static Map<String, Object> published(Info info) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("title", info.post.title()); p.put("summary", info.post.summary()); p.put("body", info.post.body());
        p.put("version", info.publishedVersion); p.put("updatedAt", info.post.updatedAt());
        return p;
    }

    /** 待处理任务的冻结快照；不含命中词（高敏感也不泄露），level 对复核任务恒为 HIGH。 */
    private Map<String, Object> submitted(Task task) {
        Map<String, Object> s = new LinkedHashMap<>();
        String title = "", summary = "", body = "";
        try { JsonNode n = json.readTree(task.snapshotJson()); title = n.path("title").asText(""); summary = n.path("summary").asText(""); body = n.path("body").asText(""); }
        catch (Exception ignored) { /* 快照损坏时仍返回任务元数据 */ }
        s.put("version", task.version()); s.put("title", title); s.put("summary", summary); s.put("body", body);
        s.put("kind", task.kind()); s.put("level", "RECHECK".equals(task.kind()) ? "HIGH" : task.level()); s.put("submittedAt", task.at());
        return s;
    }
}
