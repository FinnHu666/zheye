package com.aitome.review;

import com.aitome.access.AccessContext;
import com.aitome.access.AccessService;
import com.aitome.access.Permission;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.content.CollectionPublicationService;
import com.aitome.content.CollectionWorkspaceController.PublicEntry;
import com.aitome.content.CollectionWorkspaceController.PublicOutline;
import com.aitome.content.CollectionWorkspaceController.PublicSection;
import com.aitome.content.Comment;
import com.aitome.content.CommentRepository;
import com.aitome.content.ContentPost;
import com.aitome.content.ContentPostRepository;
import com.aitome.review.SensitiveRuleService.Detection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * FINNS-17（需规修订6）：敏感三级统一流程、人工送审与高敏感复核、下架/恢复、流转事件、低敏感留痕。
 * 判定：无命中/低 → 发布（低另留痕）；中 → 送审（REVIEW 任务）；高 → 自动不通过 + 人工复核（RECHECK 任务）。
 */
@Service
public class ReviewService {
    public static final String ARTICLE = "ARTICLE", COMMENT = "COMMENT", COLLECTION = "COLLECTION";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SensitiveRuleService rules;
    private final ReviewAudit audit;
    private final AccessService access;
    private final ContentPostRepository posts;
    private final CommentRepository comments;
    private final CollectionPublicationService publications;
    private final TransactionTemplate independent;

    public ReviewService(JdbcTemplate jdbc, ObjectMapper json, SensitiveRuleService rules, ReviewAudit audit, AccessService access,
                         ContentPostRepository posts, CommentRepository comments, CollectionPublicationService publications, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.json = json; this.rules = rules; this.audit = audit; this.access = access;
        this.posts = posts; this.comments = comments; this.publications = publications;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** outcome：PUBLISHED / PENDING（送审）/ AUTO_REJECTED（高敏感）。命中词只在中敏感时随结果返回（高敏感不返回）。 */
    public record SubmitResult(String outcome, String level, List<String> matches, Long flowId, Long taskId, Long targetId, String message, boolean replay) {
        SubmitResult asReplay() { return new SubmitResult(outcome, level, matches, flowId, taskId, targetId, message, true); }
    }

    /** 发布文章/评论时由服务调用，返回新公开对象 ID。 */
    @FunctionalInterface public interface Publisher { long publish(Long submissionId); }

    private record State(long id, long authorId, String type, Long targetId, long generation, boolean approvedOnce, boolean legacy) {}

    // ------------------------------------------------------------------ 提交

    @Transactional
    public SubmitResult submitComment(long authorId, String authorName, long postId, String contentKey, String requestId, String postTitle, String body) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", COMMENT); snapshot.put("postId", postId); snapshot.put("postTitle", postTitle); snapshot.put("body", body);
        return process(authorId, authorName, COMMENT, "COMMENT:" + postId + ":" + cleanKey(contentKey), requestId, List.of(body), ReviewText.cut(body, 40), snapshot, 1, false, null,
                submissionId -> {
                    Comment saved = comments.saveAndFlush(new Comment(postId, authorId, authorName, body));
                    if (submissionId != null) jdbc.update("update comments set review_submission_id=? where id=?", submissionId, saved.getId());
                    return saved.getId();
                });
    }

    /** 专题发布。无命中/低敏感时只返回 PUBLISHED，由调用方（持有专题行锁）完成现有发布流程；专题首次发布不再需要审批。 */
    @Transactional
    public SubmitResult submitCollection(long authorId, String authorName, long collectionId, String requestId, PublicOutline outline, String visibility, long revision) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", COLLECTION); snapshot.put("name", outline.collection().name()); snapshot.put("description", outline.collection().description());
        snapshot.put("visibility", visibility); snapshot.put("revision", revision); snapshot.put("outline", outline);
        Integer published = jdbc.queryForObject("select count(*) from collection_publications where collection_id=?", Integer.class, collectionId);
        String moderation = jdbc.queryForObject("select moderation_state from community_collections where id=?", String.class, collectionId);
        if ("TAKEN_DOWN".equals(moderation)) throw takenDown();
        return process(authorId, authorName, COLLECTION, "COLLECTION:" + collectionId, requestId, outlineTexts(outline), ReviewText.cut(outline.collection().name(), 120), snapshot, revision, published != null && published > 0, collectionId, null);
    }

    public SubmitResult collectionReplay(long authorId,long collectionId,String requestId,long version) {
        if(requestId==null||requestId.isBlank())return null;
        List<String> receipts=jdbc.queryForList("select r.response_json from review_receipts r join review_content_state s on s.id=r.state_id where s.author_id=? and s.content_type='COLLECTION' and s.target_id=? and r.request_id=?",String.class,authorId,collectionId,requestId);
        if(receipts.isEmpty())return null;
        SubmitResult result=fromJson(receipts.get(0));
        Long original=jdbc.queryForObject("select snapshot_version from review_flows where id=?",Long.class,result.flowId());
        if(original!=version)throw new ApiProblem(HttpStatus.CONFLICT,"同一请求标识不能用于不同提交版本");
        return result.asReplay();
    }

    private SubmitResult process(long authorId, String authorName, String type, String key, String requestId, List<String> texts, String title, Map<String, Object> snapshot,
                                 long snapshotVersion, boolean initialApproved, Long initialTarget, Publisher publisher) {
        Detection detection = rules.detect(texts); // 检测不可用时在建立任何状态前返回 503
        State state = lockState(authorId, type, ReviewText.cut(key, 80), initialApproved, initialTarget);
        String rid = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : ReviewText.cut(requestId.trim(), 64);
        List<String> receipt = jdbc.queryForList("select response_json from review_receipts where state_id=? and request_id=?", String.class, state.id(), rid);
        if (!receipt.isEmpty()) return fromJson(receipt.get(0)).asReplay();
        if (!COLLECTION.equals(type) && draftIdOf(key) == null && state.targetId() != null) throw new ApiProblem(HttpStatus.CONFLICT, "该内容已发布，请不要重复提交。", "ALREADY_PUBLISHED", null);
        String level = detection.level(), snapshotJson = toJson(snapshot), words = ReviewText.cut(String.join("、", detection.phrases()), 400);
        long generation = state.generation() + 1;
        invalidatePending(state.id(), null, "作者已重新提交，旧提交失效。");
        long flowId = insertFlow(state, authorId, type, title, snapshotVersion, level);
        event(flowId, "SUBMIT", "已提交", "提交版 v" + snapshotVersion, "", authorId, authorName);
        SubmitResult result;
        Long target = state.targetId();
        switch (level) {
            case "MEDIUM" -> {
                event(flowId, "CHECK", "敏感检测完成", "命中中敏感内容", "命中：" + words, null, null);
                event(flowId, "ROUTE", "送审", "内容暂不公开，等待另一位有权限的审核员处理", "", null, null);
                long taskId = insertTask(state, type, title, generation, snapshotJson, snapshotVersion, "REVIEW", "MEDIUM", words, flowId);
                result = new SubmitResult("PENDING", level, detection.phrases(), flowId, taskId, null, "内容已送审，审核通过后才会公开。", false);
            }
            case "HIGH" -> {
                event(flowId, "CHECK", "敏感检测完成", "检测发现不允许发布的内容", "命中：" + words, null, null);
                event(flowId, "ROUTE", "自动不通过", "内容不会公开；已同时转人工复核", "", null, null);
                long taskId = insertTask(state, type, title, generation, snapshotJson, snapshotVersion, "RECHECK", "HIGH", words, flowId);
                setFlowStatus(flowId, "AUTO_REJECTED");
                event(flowId, "RESULT", "未通过", "包含不允许发布的内容。人工复核结果会更新到这里；你也可以修改后重新提交。", "", null, null);
                result = new SubmitResult("AUTO_REJECTED", level, List.of(), flowId, taskId, null, "未通过：包含不允许发布的内容。已转人工复核，你可以修改后重新提交。", false);
            }
            default -> {
                boolean low = "LOW".equals(level);
                event(flowId, "CHECK", "敏感检测完成", low ? "命中低敏感内容，不拦截" : "未发现敏感内容", low ? "命中：" + words : "", null, null);
                event(flowId, "ROUTE", low ? "放行并留痕" : "直接发布", low ? "低敏感不需要人工审核，已记录供站长事后查看" : "无需人工审核", "", null, null);
                if (publisher != null) target = publisher.publish(null);
                setFlowStatus(flowId, "PUBLISHED");
                boolean planned=COLLECTION.equals(type)&&snapshot.get("outline") instanceof PublicOutline o&&o.scheduledAt()!=null;
                event(flowId, "RESULT", planned?"检测通过，待定时公开":"已发布", "提交版 v" + snapshotVersion, "", null, null);
                if (low) insertNoted(type, target, authorId, title, words);
                result = new SubmitResult("PUBLISHED", level, List.of(), flowId, null, target, low ? "内容已发布；检测到低敏感内容，已留痕。" : "敏感检测通过，内容已发布。", false);
            }
        }
        saveState(state, generation, target, state.approvedOnce() || "PUBLISHED".equals(result.outcome()) && COLLECTION.equals(type));
        jdbc.update("insert into review_receipts(state_id,request_id,response_json,created_at) values(?,?,?,?)", state.id(), rid, toJson(result), Timestamp.from(Instant.now()));
        audit.write(ReviewAudit.Row.of("SUBMIT", authorId, authorName).content(authorId, type, target, result.taskId(), snapshotVersion, level).note(words, result.outcome()));
        return result;
    }

    // ------------------------------------------------------------------ 文章草稿生命周期（FINNS-18 B3，契约修订2 §3.3–§3.9）

    public static String articleKey(long draftId) { return "ARTICLE#" + draftId; }

    /** 状态键 ARTICLE#<draftId> 的草稿 ID；旧键（客户端 draftKey）返回 null。 */
    static Long draftIdOf(String key) {
        if (key == null || !key.startsWith("ARTICLE#")) return null;
        try { return Long.parseLong(key.substring("ARTICLE#".length())); } catch (NumberFormatException e) { return null; }
    }

    private String stateKey(long stateId) {
        List<String> keys = jdbc.queryForList("select content_key from review_content_state where id=?", String.class, stateId);
        return keys.isEmpty() ? null : keys.get(0);
    }

    private static ApiProblem notPublic() { return new ApiProblem(HttpStatus.CONFLICT, "这条内容当前没有公开版或已被撤回，不能执行此操作。", "NOT_PUBLIC", null); }
    private static ApiProblem notDeletable(String reason) {
        return new ApiProblem(HttpStatus.CONFLICT, "PENDING_SUBMISSION".equals(reason) ? "有待处理的提交，请先取消送审或等待处理结果后再删除。" : "这篇文章公开过，不能删除；可以撤回公开后保留私人稿。", "NOT_DELETABLE", Map.of("reason", reason));
    }

    /** 同一 requestId 的回执回放（契约 §3.3 步骤2）。只存结果；article 由调用方现取现算。 */
    @Transactional(readOnly = true)
    public java.util.Optional<SubmitResult> articleReceipt(long authorId, long draftId, String requestId) {
        List<String> rows = jdbc.queryForList("select r.response_json from review_receipts r join review_content_state s on s.id=r.state_id where s.author_id=? and s.content_type='ARTICLE' and s.content_key=? and r.request_id=?", String.class, authorId, articleKey(draftId), cut(requestId.trim(), 64));
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(fromJson(rows.get(0)).asReplay());
    }

    private record DraftRow(long authorId, Long postId, long version, String title, String summary, String body) {}

    /** 提交事务：重新锁草稿并核对版本，快照取自锁住的草稿行（契约 §3.3 步骤4、§14 R3）。检测异常（503）发生在建立任何状态之前。 */
    @Transactional
    public SubmitResult submitArticleDraft(long authorId, String authorName, long draftId, String requestId, long expectedVersion) {
        List<DraftRow> rows = jdbc.query("select author_id,post_id,version,title,summary,body from article_drafts where id=? for update",
                (rs, n) -> new DraftRow(rs.getLong(1), (Long) rs.getObject(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)), draftId);
        if (rows.isEmpty() || rows.get(0).authorId() != authorId) throw new ApiProblem(HttpStatus.NOT_FOUND, "未找到属于当前账号的文章。");
        DraftRow draft = rows.get(0);
        if (draft.version() != expectedVersion)
            throw new ApiProblem(HttpStatus.CONFLICT, "这份草稿已在别处更新到 v" + draft.version() + "。你的输入已保留，请核对后再发布。", "VERSION_CONFLICT", Map.of("currentVersion", draft.version()));
        Long postId = draft.postId();
        if (postId != null) {
            List<String> moderation = jdbc.queryForList("select moderation_state from content_posts where id=?", String.class, postId);
            if (!moderation.isEmpty() && "TAKEN_DOWN".equals(moderation.get(0))) throw takenDown();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", ARTICLE); snapshot.put("title", draft.title()); snapshot.put("summary", draft.summary()); snapshot.put("body", draft.body());
        return process(authorId, authorName, ARTICLE, articleKey(draftId), requestId, List.of(draft.title(), draft.summary(), draft.body()), cut(draft.title(), 120), snapshot, draft.version(), postId != null, postId,
                submissionId -> publishArticle(postId, draftId, authorId, authorName, draft.title(), draft.summary(), draft.body(), submissionId));
    }

    /** 发布与审批通过共用（契约 §3.6）：无公开行则新建并写回草稿；有公开行则条件 UPDATE（只作用于 VISIBLE/WITHDRAWN），不 save 整个实体。 */
    private long publishArticle(Long postId, long draftId, long authorId, String authorName, String title, String summary, String body, Long submissionId) {
        if (postId == null) {
            ContentPost saved = posts.saveAndFlush(new ContentPost(ContentPost.Type.ARTICLE, title, summary, body, authorId, authorName));
            if (submissionId != null) jdbc.update("update content_posts set review_submission_id=? where id=?", submissionId, saved.getId());
            jdbc.update("update article_drafts set post_id=? where id=? and post_id is null", saved.getId(), draftId);
            return saved.getId();
        }
        Timestamp now = Timestamp.from(Instant.now());
        int changed = submissionId == null
                ? jdbc.update("update content_posts set title=?,summary=?,body=?,updated_at=?,moderation_state='VISIBLE' where id=? and moderation_state in ('VISIBLE','WITHDRAWN')", title, summary, body, now, postId)
                : jdbc.update("update content_posts set title=?,summary=?,body=?,updated_at=?,moderation_state='VISIBLE',review_submission_id=? where id=? and moderation_state in ('VISIBLE','WITHDRAWN')", title, summary, body, now, submissionId, postId);
        if (changed == 0) throw takenDown();
        return postId;
    }

    /** 取消送审（契约 §3.4）：只对 REVIEW 类待办任务；复核任务不可取消；写作者可见事件；工作稿与公开行不变。调用方已先锁草稿行。 */
    @Transactional
    public void cancelArticleSubmission(long authorId, String authorName, long draftId) {
        List<Long> stateIds = jdbc.queryForList("select id from review_content_state where author_id=? and content_type='ARTICLE' and content_key=?", Long.class, authorId, articleKey(draftId));
        if (stateIds.isEmpty()) throw new ApiProblem(HttpStatus.CONFLICT, "当前没有待处理的提交，可能已被处理。", "NOTHING_TO_CANCEL", null);
        State state = lockStateById(stateIds.get(0));
        List<Map<String, Object>> tasks = jdbc.queryForList("select id,kind,flow_id from review_submissions where state_id=? and status='PENDING' order by id desc", state.id());
        if (tasks.isEmpty()) throw new ApiProblem(HttpStatus.CONFLICT, "当前没有待处理的提交，可能已被处理。", "NOTHING_TO_CANCEL", null);
        Map<String, Object> task = new HashMap<>();
        tasks.get(0).forEach((k, v) -> task.put(k.toLowerCase(Locale.ROOT), v));
        if ("RECHECK".equals(String.valueOf(task.get("kind"))))
            throw new ApiProblem(HttpStatus.CONFLICT, "高敏感内容已自动不通过并转人工复核，不能取消；可以修改后重新提交。", "RECHECK_NOT_CANCELLABLE", null);
        long taskId = ((Number) task.get("id")).longValue();
        jdbc.update("update review_submissions set status='INVALID',result_note=?,closed_at=? where id=?", "作者取消送审", Timestamp.from(Instant.now()), taskId);
        if (task.get("flow_id") != null) {
            long flowId = ((Number) task.get("flow_id")).longValue();
            setFlowStatus(flowId, "INVALID");
            event(flowId, "RESULT", "已取消送审", "只取消这次提交；工作稿与旧公开版保留。", "", authorId, authorName);
        }
        saveState(state, state.generation() + 1, state.targetId(), state.approvedOnce());
        audit.write(ReviewAudit.Row.of("WITHDRAW", authorId, authorName).content(authorId, ARTICLE, state.targetId(), taskId, null, null).note("", "作者取消送审"));
    }

    /** 撤回公开（契约 §3.5）：只作用于 VISIBLE；使待审提交失效；事件追加到最近一条 PUBLISHED 流转（没有则建合成流转）。调用方已先锁草稿行并核对二次确认。 */
    @Transactional
    public void withdrawArticle(long authorId, String authorName, long draftId, Long postId, String title, long version) {
        if (postId == null) throw notPublic();
        State state = lockState(authorId, ARTICLE, articleKey(draftId), true, postId);
        List<String> moderation = jdbc.queryForList("select moderation_state from content_posts where id=?", String.class, postId);
        if (moderation.isEmpty()) throw notPublic();
        if ("TAKEN_DOWN".equals(moderation.get(0))) throw takenDown();
        if (!"VISIBLE".equals(moderation.get(0))) throw notPublic();
        invalidatePending(state.id(), null, "作者撤回，旧提交失效。");
        if (jdbc.update("update content_posts set moderation_state='WITHDRAWN' where id=? and moderation_state='VISIBLE'", postId) == 0) throw takenDown();
        List<Long> published = jdbc.queryForList("select id from review_flows where state_id=? and status='PUBLISHED' order by id desc limit 1", Long.class, state.id());
        long flowId;
        if (published.isEmpty()) { flowId = insertFlow(state, authorId, ARTICLE, cut(title, 120), version, "NONE"); setFlowStatus(flowId, "PUBLISHED"); }
        else flowId = published.get(0);
        event(flowId, "RESULT", "作者撤回", "私人原稿保留", "", authorId, authorName);
        saveState(state, state.generation() + 1, postId, state.approvedOnce());
        audit.write(ReviewAudit.Row.of("WITHDRAW", authorId, authorName).content(authorId, ARTICLE, postId, null, version, null).note("", "作者撤回公开内容"));
    }

    /** 删除从未公开的私人草稿的审核痕迹（契约 §3.9）：显式按序删除（H2 无级联），审计只留不含正文的元数据。调用方已先锁草稿行并确认 post_id 为空。 */
    @Transactional
    public void deleteArticleTraces(long authorId, String authorName, long draftId) {
        List<Long> stateIds = jdbc.queryForList("select id from review_content_state where author_id=? and content_type='ARTICLE' and content_key=?", Long.class, authorId, articleKey(draftId));
        if (!stateIds.isEmpty()) {
            long stateId = stateIds.get(0);
            jdbc.queryForList("select id from review_content_state where id=? for update", Long.class, stateId);
            Integer pending = jdbc.queryForObject("select count(*) from review_submissions where state_id=? and status='PENDING'", Integer.class, stateId);
            if (pending != null && pending > 0) throw notDeletable("PENDING_SUBMISSION");
            jdbc.update("delete from review_flow_events where flow_id in (select id from review_flows where state_id=?)", stateId);
            jdbc.update("delete from review_flows where state_id=?", stateId);
            jdbc.update("delete from review_submissions where state_id=?", stateId);
            jdbc.update("delete from review_receipts where state_id=?", stateId);
            jdbc.update("delete from review_content_state where id=?", stateId);
        }
        audit.write(ReviewAudit.Row.of("DELETE_DRAFT", authorId, authorName).content(authorId, "DRAFT", draftId, null, null, null).note("", "删除私人草稿"));
    }

    @Transactional
    public void deleteCollectionTraces(long authorId,String authorName,long collectionId) {
        List<Long> ids=jdbc.queryForList("select id from review_content_state where author_id=? and content_type='COLLECTION' and target_id=? for update",Long.class,authorId,collectionId);
        for (Long stateId:ids) {
            if (jdbc.queryForObject("select count(*) from review_submissions where state_id=? and status='PENDING'",Integer.class,stateId)>0) throw new ApiProblem(HttpStatus.CONFLICT,"待处理提交的专题不能删除");
            jdbc.update("delete from review_flow_events where flow_id in (select id from review_flows where state_id=?)",stateId);
            jdbc.update("delete from review_flows where state_id=?",stateId);
            jdbc.update("delete from review_submissions where state_id=?",stateId);
            jdbc.update("delete from review_receipts where state_id=?",stateId);
            jdbc.update("delete from review_content_state where id=?",stateId);
        }
        audit.write(ReviewAudit.Row.of("DELETE_DRAFT",authorId,authorName).content(authorId,COLLECTION,collectionId,null,null,null).note("","删除私人专题草稿"));
    }

    // ------------------------------------------------------------------ 审批决定

    /** REVIEW 任务：APPROVE / REJECT；RECHECK 任务：UPHOLD（维持不通过）/ OVERTURN（判定误判并放行，发布提交快照）。 */
    @Transactional
    public Map<String, Object> decide(long operatorId, long taskId, String action, Long expectedGeneration, String note) {
        AccessContext operator = requireReviewer(operatorId);
        Map<String, Object> task = loadTask(taskId);
        State peek = stateById(((Number) task.get("state_id")).longValue());
        if (COLLECTION.equals(peek.type()) && peek.targetId() != null) jdbc.queryForObject("select id from community_collections where id=? for update", Long.class, peek.targetId());
        Long peekDraft = ARTICLE.equals(peek.type()) ? draftIdOf(stateKey(peek.id())) : null;
        if (peekDraft != null) jdbc.queryForList("select id from article_drafts where id=? for update", Long.class, peekDraft); // 锁序：草稿 → 状态
        State state = lockStateById(peek.id());
        String kind = String.valueOf(task.get("kind"));
        if (state.authorId() == operator.userId()) {
            audit.writeIndependent(ReviewAudit.Row.of(auditType(action), operator.userId(), operator.displayName())
                    .content(state.authorId(), state.type(), state.targetId(), taskId, null, null).outcome("REJECTED").note(note, "禁止审核本人内容"));
            throw new ApiProblem(HttpStatus.FORBIDDEN, "禁止审核本人内容，请由另一位有审批权限的人处理。", "SELF_REVIEW", null);
        }
        task = loadTask(taskId);
        long taskGeneration = ((Number) task.get("generation")).longValue();
        if (!"PENDING".equals(task.get("status")) || taskGeneration != state.generation() || expectedGeneration == null || expectedGeneration != taskGeneration || !authorActive(state.authorId()))
            throw new ApiProblem(HttpStatus.CONFLICT, "该提交已处理或失效，请重新读取，未重复发布。", "STALE", null);
        List<String> allowed = "RECHECK".equals(kind) ? List.of("UPHOLD", "OVERTURN") : List.of("APPROVE", "REJECT");
        if (action == null || !allowed.contains(action)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "处理操作无效。");
        String cleaned = note == null ? "" : note.trim();
        if (("RECHECK".equals(kind) || "REJECT".equals(action)) && cleaned.isEmpty()) throw new ApiProblem(HttpStatus.BAD_REQUEST, "RECHECK".equals(kind) ? "请填写复核原因。" : "请填写驳回原因。");
        if (cleaned.length() > 200) throw new ApiProblem(HttpStatus.BAD_REQUEST, "处理说明最多200字。");
        long version = ((Number) task.get("snapshot_version")).longValue();
        long flowId = task.get("flow_id") == null ? 0 : ((Number) task.get("flow_id")).longValue();
        Timestamp now = Timestamp.from(Instant.now());
        boolean publish = "APPROVE".equals(action) || "OVERTURN".equals(action);
        String status = switch (action) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; case "UPHOLD" -> "UPHELD"; default -> "OVERTURNED"; };
        String title = switch (action) { case "APPROVE" -> "审核通过"; case "REJECT" -> "审核驳回"; case "UPHOLD" -> "复核：维持不通过"; default -> "复核：判定误判并放行"; };
        String message;
        if (publish) {
            JsonNode snapshot = parse((String) task.get("snapshot_json"));
            Long target = state.targetId();
            switch (state.type()) {
                case ARTICLE -> {
                    Long draftId = draftIdOf(stateKey(state.id()));
                    if (draftId != null) {
                        if (target != null) {
                            List<String> moderation = jdbc.queryForList("select moderation_state from content_posts where id=?", String.class, target);
                            if (!moderation.isEmpty() && "TAKEN_DOWN".equals(moderation.get(0))) { // 下架优先：中止并使该提交失效，不覆盖下架
                                invalidatePending(state.id(), null, "内容已被人工下架，该提交已失效。");
                                saveState(state, state.generation() + 1, target, state.approvedOnce());
                                return Map.of("message", "内容已被人工下架，该提交已失效，未发布。");
                            }
                        }
                        target = publishArticle(target, draftId, state.authorId(), authorName(state.authorId()), snapshot.path("title").asText(), snapshot.path("summary").asText(), snapshot.path("body").asText(), taskId);
                    } else { // 旧键（客户端 draftKey，开发库遗留）：沿用原行为
                        ContentPost saved = posts.saveAndFlush(new ContentPost(ContentPost.Type.ARTICLE, snapshot.path("title").asText(), snapshot.path("summary").asText(), snapshot.path("body").asText(), state.authorId(), authorName(state.authorId())));
                        jdbc.update("update content_posts set review_submission_id=? where id=?", taskId, saved.getId());
                        target = saved.getId();
                    }
                }
                case COMMENT -> {
                    long postId = snapshot.path("postId").asLong();
                    Integer visible = jdbc.queryForObject("select count(*) from content_posts where id=? and moderation_state='VISIBLE'", Integer.class, postId);
                    if (visible == null || visible == 0) throw new ApiProblem(HttpStatus.CONFLICT, "评论所属内容已不可用，不能发布。", "STALE", null);
                    Comment saved = comments.saveAndFlush(new Comment(postId, state.authorId(), authorName(state.authorId()), snapshot.path("body").asText()));
                    jdbc.update("update comments set review_submission_id=? where id=?", taskId, saved.getId());
                    target = saved.getId();
                }
                default -> {
                    long collectionId = state.targetId();
                    List<Map<String, Object>> rows = jdbc.queryForList("select owner_id,moderation_state,status from community_collections where id=?", collectionId);
                    if (rows.isEmpty() || !Objects.equals(((Number) rows.get(0).get("owner_id")).longValue(), state.authorId()) || "TAKEN_DOWN".equals(rows.get(0).get("moderation_state")))
                        throw new ApiProblem(HttpStatus.CONFLICT, "专题已不可发布，请让作者重新提交。", "STALE", null);
                    try {
                        boolean immediate = publications.activate(collectionId, json.treeToValue(snapshot.get("outline"), PublicOutline.class), snapshot.path("visibility").asText("PUBLIC"));
                        if (immediate) jdbc.update("update community_collections set status='PUBLISHED',published_at=coalesce(published_at,current_timestamp) where id=?", collectionId);
                    } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read submission snapshot", e); }
                }
            }
            jdbc.update("update review_submissions set status=?,result_note=?,operator_id=?,operator_name=?,closed_at=? where id=?", status, cleaned.isEmpty() ? "审核通过" : cleaned, operator.userId(), cut(operator.displayName(), 40), now, taskId);
            invalidatePending(state.id(), taskId, "同一内容已有审批发布，旧提交失效。");
            if (flowId > 0) {
                event(flowId, "REVIEW", title, cleaned.isEmpty() ? "审核员已处理" : cleaned, "", operator.userId(), operator.displayName());
                setFlowStatus(flowId, "PUBLISHED");
                boolean planned=COLLECTION.equals(state.type())&&jdbc.queryForObject("select scheduled_at from community_collections where id=?",Timestamp.class,state.targetId())!=null;
                event(flowId, "RESULT", planned?"审批通过，待定时公开":"已发布", "提交版 v" + version, "", null, null);
            }
            saveState(state, state.generation() + 1, target, true);
            message = "审批通过，已发布提交版本 v" + version + "。";
        } else {
            if (COLLECTION.equals(state.type()) && state.targetId()!=null) publications.clearSchedule(state.targetId());
            jdbc.update("update review_submissions set status=?,result_note=?,operator_id=?,operator_name=?,closed_at=? where id=?", status, cleaned, operator.userId(), cut(operator.displayName(), 40), now, taskId);
            if (flowId > 0) {
                event(flowId, "REVIEW", title, cleaned, "", operator.userId(), operator.displayName());
                setFlowStatus(flowId, "UPHOLD".equals(action) ? "UPHELD" : "REJECTED");
                event(flowId, "RESULT", "未通过", cleaned, "", null, null);
            }
            message = "已记录处理结果，原因已向作者展示；旧有效版保持。";
        }
        audit.write(ReviewAudit.Row.of(auditType(action), operator.userId(), operator.displayName())
                .content(state.authorId(), state.type(), state.targetId(), taskId, version, kind).note(cleaned, message));
        return Map.of("message", message);
    }

    private static String auditType(String action) { return "APPROVE".equals(action) || "REJECT".equals(action) || "UPHOLD".equals(action) || "OVERTURN".equals(action) ? action : "REJECT"; }

    // ------------------------------------------------------------------ 下架 / 恢复

    @Transactional
    public Map<String, Object> manage(long operatorId, String type, long targetId, String action, String expectedModeration, String note, boolean acknowledgeMatches) {
        AccessContext operator = requireReviewer(operatorId);
        if (!List.of(ARTICLE, COMMENT, COLLECTION).contains(type)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "内容类型无效。");
        if (!"TAKE_DOWN".equals(action) && !"RESTORE".equals(action)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "处理操作无效。");
        String cleaned = note == null ? "" : note.trim();
        if (cleaned.isEmpty()) throw new ApiProblem(HttpStatus.BAD_REQUEST, "请填写处理原因。");
        if (cleaned.length() > 200) throw new ApiProblem(HttpStatus.BAD_REQUEST, "处理原因最多200字。");
        if (COLLECTION.equals(type)) jdbc.queryForList("select id from community_collections where id=? for update", targetId);
        Published published = publishedOf(type, targetId);
        if ("WITHDRAWN".equals(published.moderation())) throw new ApiProblem(HttpStatus.CONFLICT, "内容已被作者撤回，不能下架或恢复。", "NOT_PUBLIC", null);
        State state = lockStateForTarget(type, targetId, published.authorId());
        if ("RESTORE".equals(action) && published.authorId() == operator.userId()) {
            audit.writeIndependent(ReviewAudit.Row.of("RESTORE", operator.userId(), operator.displayName()).content(published.authorId(), type, targetId, null, null, null).outcome("REJECTED").note(cleaned, "禁止批准恢复本人作品"));
            throw new ApiProblem(HttpStatus.FORBIDDEN, "禁止批准恢复本人作品，请交由另一位审批者。", "SELF_REVIEW", null);
        }
        boolean down = "TAKEN_DOWN".equals(published.moderation());
        if (expectedModeration != null && !expectedModeration.equals(published.moderation()) || "RESTORE".equals(action) != down)
            throw new ApiProblem(HttpStatus.CONFLICT, "发布状态已变化，请重新读取。", "STALE", null);
        String triggers = null;
        if ("RESTORE".equals(action)) {
            Detection detection = rules.detect(published.texts());
            if (SensitiveRuleService.rank(detection.level()) >= 2) { // 中/高命中：恢复动作本身即合并的敏感复核，须显式确认
                if (!acknowledgeMatches) throw new ApiProblem(HttpStatus.CONFLICT, "恢复内容命中敏感规则，请阅读全文后确认再恢复。", "MATCHED", Map.of("matches", detection.phrases()));
                triggers = "RESTORE,SENSITIVE";
            }
        }
        String next = "TAKE_DOWN".equals(action) ? "TAKEN_DOWN" : "VISIBLE";
        jdbc.update("update " + tableOf(type) + " set moderation_state=? where id=?", next, targetId);
        invalidatePending(state.id(), null, "内容处置使旧提交失效。");
        saveState(state, state.generation() + 1, targetId, state.approvedOnce());
        String message = "TAKE_DOWN".equals(action) ? "已下架，公开入口不再显示该内容。" : "已复核恢复指定发布版，工作稿未替换它。";
        audit.write(ReviewAudit.Row.of(action, operator.userId(), operator.displayName()).content(published.authorId(), type, targetId, null, null, triggers).note(cleaned, message));
        return Map.of("message", message);
    }

    // ------------------------------------------------------------------ 作者侧生命周期

    /** 作者撤回/归档专题：使待审提交失效，旧审批不能重新公开。 */
    @Transactional
    public void collectionWithdrawn(long collectionId, long authorId, String authorName) {
        List<Long> ids = jdbc.queryForList("select id from review_content_state where content_type='COLLECTION' and target_id=?", Long.class, collectionId);
        if (ids.isEmpty()) return;
        State state = lockStateById(ids.get(0));
        if (invalidatePending(state.id(), null, "作者撤回，旧提交失效。") > 0) {
            saveState(state, state.generation() + 1, state.targetId(), state.approvedOnce());
            audit.write(ReviewAudit.Row.of("WITHDRAW", authorId, authorName).content(authorId, COLLECTION, collectionId, null, null, null).note("", "作者撤回专题"));
        }
    }

    @Transactional
    public Map<String, Object> withdrawTask(long authorId, String authorName, long taskId) {
        Map<String, Object> task = loadTask(taskId);
        State peek = stateById(((Number)task.get("state_id")).longValue());
        if (COLLECTION.equals(peek.type()) && peek.targetId()!=null) jdbc.queryForObject("select id from community_collections where id=? for update",Long.class,peek.targetId());
        if (((Number) task.get("author_id")).longValue() != authorId) throw new ApiProblem(HttpStatus.NOT_FOUND, "未找到属于当前账号的提交。");
        State state = lockStateById(((Number) task.get("state_id")).longValue());
        task = loadTask(taskId);
        if (!"PENDING".equals(task.get("status"))) throw new ApiProblem(HttpStatus.CONFLICT, "该提交已处理或失效。", "STALE", null);
        if ((ARTICLE.equals(state.type()) || COLLECTION.equals(state.type())) && "RECHECK".equals(String.valueOf(task.get("kind"))))
            throw new ApiProblem(HttpStatus.CONFLICT, "高敏感内容已自动不通过并转人工复核，不能取消；可以修改后重新提交。", "RECHECK_NOT_CANCELLABLE", null);
        invalidatePending(state.id(), null, "作者已撤回，私人原稿保留。");
        if (COLLECTION.equals(state.type()) && state.targetId()!=null) publications.clearSchedule(state.targetId());
        saveState(state, state.generation() + 1, state.targetId(), state.approvedOnce());
        audit.write(ReviewAudit.Row.of("WITHDRAW", authorId, authorName).content(authorId, state.type(), state.targetId(), taskId, null, null).note("", "作者撤回待审提交"));
        return Map.of("message", "已撤回，私人原稿保留。");
    }

    // ------------------------------------------------------------------ 读取

    @Transactional
    public Map<String, Object> queue(long operatorId, String status, String type, String level, int days, String q, int page, int size) {
        requireReviewer(operatorId);
        sweep();
        int safePage = Math.max(0, Math.min(page, 100_000)), safeSize = Math.max(1, Math.min(size, 50));
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        String mode = status == null || status.isBlank() ? "PENDING" : status;
        switch (mode) {
            case "PENDING", "APPROVED", "REJECTED", "UPHELD", "OVERTURNED", "INVALID" -> { where.append(" and s.status=?"); args.add(mode); }
            case "DONE" -> where.append(" and s.status<>'PENDING'");
            default -> { }
        }
        if (type != null && !type.isBlank()) { where.append(" and s.content_type=?"); args.add(type); }
        if (level != null && SensitiveRuleService.LEVELS.contains(level)) { where.append(" and s.level=?"); args.add(level); }
        if (days > 0) { where.append(" and s.created_at>=?"); args.add(Timestamp.from(Instant.now().minusSeconds(86_400L * days))); }
        if (q != null && !q.isBlank()) {
            String like = "%" + ReviewText.escapeLike(q.trim().toLowerCase()) + "%";
            where.append(" and (lower(s.title) like ? escape '\\' or lower(u.display_name) like ? escape '\\')");
            args.add(like); args.add(like);
        }
        String from = " from review_submissions s join user_accounts u on u.id=s.author_id" + where;
        Long total = jdbc.queryForObject("select count(*)" + from, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(safeSize); pageArgs.add((long) safePage * safeSize);
        List<Map<String, Object>> items = jdbc.query("select s.id,s.content_type,s.title,s.author_id,u.display_name,s.kind,s.level,s.matched_phrases,s.status,s.result_note,s.operator_name,s.created_at,s.closed_at,s.generation,s.snapshot_version"
                + from + " order by s.created_at desc,s.id desc limit ? offset ?", (rs, n) -> taskSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant(), rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(), rs.getLong(14), rs.getLong(15), operatorId), pageArgs.toArray());
        Timestamp today = Timestamp.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items); body.put("total", total == null ? 0 : total); body.put("page", safePage); body.put("size", safeSize);
        body.put("pending", count("select count(*) from review_submissions where status='PENDING'"));
        body.put("medium", count("select count(*) from review_submissions where status='PENDING' and level='MEDIUM'"));
        body.put("high", count("select count(*) from review_submissions where status='PENDING' and level='HIGH'"));
        Long doneToday = jdbc.queryForObject("select count(*) from review_submissions where status in ('APPROVED','REJECTED','UPHELD','OVERTURNED') and closed_at>=?", Long.class, today);
        body.put("doneToday", doneToday == null ? 0 : doneToday);
        body.put("low", count("select count(*) from review_noted"));
        return body;
    }

    @Transactional
    public Map<String, Object> taskDetail(long operatorId, long taskId) {
        requireReviewer(operatorId);
        sweep();
        Map<String, Object> task = loadTask(taskId);
        State state = stateById(((Number) task.get("state_id")).longValue());
        Map<String, Object> view = taskSummary(taskId, (String) task.get("content_type"), (String) task.get("title"), state.authorId(), authorName(state.authorId()), String.valueOf(task.get("kind")), String.valueOf(task.get("level")),
                (String) task.get("matched_phrases"), (String) task.get("status"), (String) task.get("result_note"), (String) task.get("operator_name"),
                instant(task.get("created_at")), task.get("closed_at") == null ? null : instant(task.get("closed_at")), ((Number) task.get("generation")).longValue(), ((Number) task.get("snapshot_version")).longValue(), operatorId);
        view.put("snapshot", parse((String) task.get("snapshot_json")));
        view.put("published", state.targetId() == null ? null : publishedView(state.type(), state.targetId()));
        view.put("otherReviewerAvailable", otherReviewerAvailable(state.authorId()));
        view.put("history", history(taskId, state.type(), state.targetId()));
        Object flowId = task.get("flow_id");
        view.put("flow", flowId == null ? null : flowView(((Number) flowId).longValue(), true));
        return view;
    }

    @Transactional
    public Map<String, Object> publications(long operatorId, String type, String moderation, String q, int page, int size) {
        requireReviewer(operatorId);
        int safePage = Math.max(0, Math.min(page, 100_000)), safeSize = Math.max(1, Math.min(size, 50));
        String union = "select 'ARTICLE' as t, id, cast(title as varchar(240)) as title, author_id, author_name, created_at as ts, moderation_state from content_posts where type='ARTICLE' "
                + "union all select 'COMMENT', id, cast(substring(body,1,40) as varchar(240)), author_id, author_name, created_at, moderation_state from comments "
                + "union all select 'COLLECTION', c.id, cast(c.name as varchar(240)), c.owner_id, c.owner_name, p.updated_at, c.moderation_state from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED'";
        StringBuilder where = new StringBuilder(" where moderation_state<>'WITHDRAWN'");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) { where.append(" and t=?"); args.add(type); }
        if (moderation != null && !moderation.isBlank()) { where.append(" and moderation_state=?"); args.add(moderation); }
        if (q != null && !q.isBlank()) {
            String like = "%" + ReviewText.escapeLike(q.trim().toLowerCase()) + "%";
            where.append(" and (lower(title) like ? escape '\\' or lower(author_name) like ? escape '\\')");
            args.add(like); args.add(like);
        }
        Long total = jdbc.queryForObject("select count(*) from (" + union + ") x" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(safeSize); pageArgs.add((long) safePage * safeSize);
        List<Map<String, Object>> items = jdbc.query("select t,id,title,author_id,author_name,ts,moderation_state from (" + union + ") x" + where + " order by ts desc,id desc limit ? offset ?", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", rs.getString(1)); row.put("id", rs.getLong(2)); row.put("title", rs.getString(3)); row.put("authorId", rs.getLong(4)); row.put("authorName", rs.getString(5));
            row.put("publishedAt", rs.getTimestamp(6).toInstant()); row.put("moderation", rs.getString(7)); row.put("down", "TAKEN_DOWN".equals(rs.getString(7))); row.put("self", rs.getLong(4) == operatorId);
            return row;
        }, pageArgs.toArray());
        return Map.of("items", items, "total", total == null ? 0 : total, "page", safePage, "size", safeSize);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicationDetail(long operatorId, String type, long targetId) {
        requireReviewer(operatorId);
        Published published = publishedOf(type, targetId);
        if ("WITHDRAWN".equals(published.moderation())) throw new ApiProblem(HttpStatus.NOT_FOUND, "未找到可处置的发布版。");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("type", type); view.put("id", targetId); view.put("authorId", published.authorId()); view.put("authorName", authorName(published.authorId()));
        view.put("moderation", published.moderation()); view.put("down", "TAKEN_DOWN".equals(published.moderation())); view.put("self", published.authorId() == operatorId);
        view.put("content", publishedView(type, targetId));
        view.put("history", history(null, type, targetId));
        return view;
    }

    /** 作者视角：我的每次提交及其流转（审核员姓名→“审核员”，高敏感不含命中词与详情）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> mine(long authorId, int page, int size, String type) {
        int safePage = Math.max(0, Math.min(page, 100_000)), safeSize = Math.max(1, Math.min(size, 50));
        String kind = type == null ? "" : type.trim().toUpperCase();
        if (!kind.isEmpty() && !List.of("ARTICLE", "COMMENT", "COLLECTION").contains(kind)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "不支持的内容类型");
        String filter = kind.isEmpty() ? "" : " and content_type='" + kind + "'"; // kind 已白名单校验
        Long total = jdbc.queryForObject("select count(*) from review_flows where author_id=?" + filter, Long.class, authorId);
        List<Long> ids = jdbc.queryForList("select id from review_flows where author_id=?" + filter + " order by started_at desc,id desc limit ? offset ?", Long.class, authorId, safeSize, (long) safePage * safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> flow = flowView(id, false);
            List<Long> pending = jdbc.queryForList("select id from review_submissions where flow_id=? and status='PENDING'", Long.class, id);
            flow.put("taskId", pending.isEmpty() ? null : pending.get(0));
            items.add(flow);
        }
        return Map.of("items", items, "total", total == null ? 0 : total, "page", safePage, "size", safeSize);
    }

    private static String contentType(String type) {
        String kind = type == null ? "" : type.trim().toUpperCase();
        if (!List.of(ARTICLE, COMMENT, COLLECTION).contains(kind)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "不支持的内容类型");
        return kind;
    }

    /** 作者视角：按已发布/已创建内容的 id 取各自的流转（每条内容最近 5 次提交）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> flowsByContent(long authorId, String type, List<Long> ids) {
        String kind = contentType(type);
        List<Long> safe = ids.stream().distinct().limit(50).toList();
        Map<String, List<Map<String, Object>>> byContent = new LinkedHashMap<>();
        if (!safe.isEmpty()) {
            String marks = String.join(",", Collections.nCopies(safe.size(), "?"));
            List<Object> args = new ArrayList<>(List.of(authorId, kind)); args.addAll(safe);
            jdbc.query("select f.id,s.target_id from review_flows f join review_content_state s on s.id=f.state_id where f.author_id=? and f.content_type=? and s.target_id in (" + marks + ") order by f.started_at desc,f.id desc",
                    (rs) -> {
                        List<Map<String, Object>> list = byContent.computeIfAbsent(String.valueOf(rs.getLong(2)), k -> new ArrayList<>());
                        if (list.size() < 5) list.add(authorFlow(rs.getLong(1)));
                    }, args.toArray());
        }
        return Map.of("items", byContent);
    }

    /** 作者视角：尚未发布成功的内容（审核中、未通过、被驳回）的最近一次流转，每个草稿内容一条。 */
    @Transactional(readOnly = true)
    public Map<String, Object> unpublishedFlows(long authorId, String type) {
        String kind = contentType(type);
        List<Long> ids = jdbc.queryForList("select max(f.id) from review_flows f join review_content_state s on s.id=f.state_id where f.author_id=? and f.content_type=? and s.target_id is null group by f.state_id order by max(f.id) desc limit 50", Long.class, authorId, kind);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids) items.add(authorFlow(id));
        return Map.of("items", items);
    }

    /** 文章草稿视图用：某审核状态下最近的流转（按最近事件时间倒序，保证撤回等追加事件的流转不被挤出），作者视角。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> articleFlows(long stateId, int limit) {
        List<Long> ids = jdbc.queryForList("select f.id from review_flows f where f.state_id=? order by coalesce((select max(e.occurred_at) from review_flow_events e where e.flow_id=f.id), f.started_at) desc, f.id desc limit ?", Long.class, stateId, Math.max(1, Math.min(limit, 20)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : ids) items.add(authorFlow(id));
        return items;
    }

    private Map<String, Object> authorFlow(long id) {
        Map<String, Object> flow = flowView(id, false);
        List<Long> pending = jdbc.queryForList("select id from review_submissions where flow_id=? and status='PENDING'", Long.class, id);
        flow.put("taskId", pending.isEmpty() ? null : pending.get(0));
        return flow;
    }

    /** 低敏感放行留痕列表（审批人）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> noted(long operatorId, int page, int size) {
        requireReviewer(operatorId);
        int safePage = Math.max(0, Math.min(page, 100_000)), safeSize = Math.max(1, Math.min(size, 50));
        Long total = jdbc.queryForObject("select count(*) from review_noted", Long.class);
        List<Map<String, Object>> items = jdbc.query("select id,content_type,content_id,author_id,title,words,created_at,reviewed_by_name,reviewed_at from review_noted order by created_at desc,id desc limit ? offset ?", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String type = rs.getString(2); long contentId = rs.getLong(3);
            row.put("id", rs.getLong(1)); row.put("type", type); row.put("contentId", contentId); row.put("authorId", rs.getLong(4)); row.put("authorName", authorName(rs.getLong(4)));
            row.put("title", rs.getString(5)); row.put("words", Arrays.asList(rs.getString(6).split("、"))); row.put("createdAt", rs.getTimestamp(7).toInstant());
            row.put("reviewed", rs.getTimestamp(9) != null); row.put("reviewedBy", rs.getString(8)); row.put("reviewedAt", rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant());
            row.put("published", stillPublic(type, contentId));
            return row;
        }, safeSize, (long) safePage * safeSize);
        return Map.of("items", items, "total", total == null ? 0 : total, "page", safePage, "size", safeSize);
    }

    @Transactional
    public Map<String, Object> markNoted(long operatorId, long id, boolean reviewed) {
        AccessContext operator = requireReviewer(operatorId);
        int updated = reviewed
                ? jdbc.update("update review_noted set reviewed_by=?,reviewed_by_name=?,reviewed_at=? where id=?", operator.userId(), cut(operator.displayName(), 40), Timestamp.from(Instant.now()), id)
                : jdbc.update("update review_noted set reviewed_by=null,reviewed_by_name=null,reviewed_at=null where id=?", id);
        if (updated == 0) throw new ApiProblem(HttpStatus.NOT_FOUND, "记录不存在。");
        audit.write(ReviewAudit.Row.of("NOTED", operator.userId(), operator.displayName()).note("", reviewed ? "标记已阅" : "取消已阅"));
        return Map.of("message", reviewed ? "已标记为已阅。" : "已取消已阅标记。");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> auditPage(long operatorId, int page, int size) {
        requireReviewer(operatorId);
        int safePage = Math.max(0, Math.min(page, 100_000)), safeSize = Math.max(1, Math.min(size, 50));
        String ruleTypes = "('RULE_CREATE','RULE_UPDATE','RULE_DELETE','BATCH_RULES','IMPORT_RULES')";
        Long total = jdbc.queryForObject("select count(*) from review_audit_log where type not in " + ruleTypes, Long.class);
        List<Map<String, Object>> items = jdbc.query("select id,type,outcome,operator_name,content_type,content_id,submission_id,snapshot_version,triggers,note,result_message,created_at from review_audit_log where type not in " + ruleTypes + " order by created_at desc,id desc limit ? offset ?",
                (rs, n) -> auditRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), (Long) rs.getObject(6), (Long) rs.getObject(7), (Long) rs.getObject(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant()), safeSize, (long) safePage * safeSize);
        return Map.of("items", items, "total", total == null ? 0 : total, "page", safePage, "size", safeSize);
    }

    // ------------------------------------------------------------------ 流转与任务（内部）

    private long insertFlow(State state, long authorId, String type, String title, long version, String level) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("insert into review_flows(state_id,author_id,content_type,title,snapshot_version,level,status,started_at) values(?,?,?,?,?,?,'IN_REVIEW',?)", new String[]{"id"});
            ps.setLong(1, state.id()); ps.setLong(2, authorId); ps.setString(3, type); ps.setString(4, title == null ? "" : title); ps.setLong(5, version); ps.setString(6, level); ps.setTimestamp(7, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private void setFlowStatus(long flowId, String status) { jdbc.update("update review_flows set status=? where id=?", status, flowId); }

    private void event(long flowId, String stage, String title, String note, String detail, Long actorId, String actorName) {
        jdbc.update("insert into review_flow_events(flow_id,stage,title,note,detail,actor_id,actor_name,occurred_at) values(?,?,?,?,?,?,?,?)",
                flowId, stage, ReviewText.cut(title, 60), ReviewText.cut(note == null ? "" : note, 200), ReviewText.cut(detail == null ? "" : detail, 400), actorId, ReviewText.cut(actorName, 40), Timestamp.from(Instant.now()));
    }

    /** reviewer=true：含处理人姓名与命中详情；否则作者视角（姓名→“审核员”，高敏感剔除详情）。 */
    private Map<String, Object> flowView(long flowId, boolean reviewer) {
        Map<String, Object> flow = jdbc.queryForObject("select id,content_type,title,snapshot_version,level,status,started_at from review_flows where id=?", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong(1)); row.put("type", rs.getString(2)); row.put("title", rs.getString(3)); row.put("version", rs.getLong(4)); row.put("level", rs.getString(5));
            row.put("status", rs.getString(6)); row.put("startedAt", rs.getTimestamp(7).toInstant());
            return row;
        }, flowId);
        if (COLLECTION.equals(flow.get("type")) && "PUBLISHED".equals(flow.get("status"))) {
            List<Timestamp> due=jdbc.queryForList("select c.scheduled_at from community_collections c join review_content_state s on s.target_id=c.id and s.content_type='COLLECTION' join review_flows f on f.state_id=s.id where f.id=? and c.scheduled_ready=true and f.id=(select max(f2.id) from review_flows f2 where f2.state_id=s.id)",Timestamp.class,flowId);
            if(!due.isEmpty()&&due.get(0)!=null){flow.put("status","SCHEDULED");flow.put("scheduledAt",due.get(0).toInstant());}
        }
        boolean high = "HIGH".equals(flow.get("level"));
        List<Map<String, Object>> events = jdbc.query("select id,stage,title,note,detail,actor_name,occurred_at from review_flow_events where flow_id=? order by occurred_at,id", (rs, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            String actor = rs.getString(6);
            e.put("id", rs.getLong(1)); e.put("stage", rs.getString(2)); e.put("title", rs.getString(3)); e.put("note", rs.getString(4));
            e.put("detail", reviewer || !high ? rs.getString(5) : ""); e.put("actor", actor == null ? "" : reviewer ? actor : "审核员"); e.put("at", rs.getTimestamp(7).toInstant());
            return e;
        }, flowId);
        flow.put("events", events);
        return flow;
    }

    private void insertNoted(String type, Long contentId, long authorId, String title, String words) {
        jdbc.update("insert into review_noted(content_type,content_id,author_id,title,words,created_at) values(?,?,?,?,?,?)", type, contentId, authorId, ReviewText.cut(title, 120), words, Timestamp.from(Instant.now()));
    }

    private boolean stillPublic(String type, long contentId) {
        Integer n = switch (type) {
            case ARTICLE -> jdbc.queryForObject("select count(*) from content_posts where id=? and moderation_state='VISIBLE'", Integer.class, contentId);
            case COMMENT -> jdbc.queryForObject("select count(*) from comments where id=? and moderation_state='VISIBLE'", Integer.class, contentId);
            default -> jdbc.queryForObject("select count(*) from community_collections c join collection_publications p on p.collection_id=c.id where c.id=? and c.status='PUBLISHED' and c.moderation_state='VISIBLE'", Integer.class, contentId);
        };
        return n != null && n > 0;
    }

    private long insertTask(State state, String type, String title, long generation, String snapshotJson, long snapshotVersion, String kind, String level, String words, long flowId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("insert into review_submissions(state_id,author_id,content_type,title,generation,snapshot_json,snapshot_version,triggers,matched_phrases,attempts,status,result_note,created_at,kind,level,flow_id) values(?,?,?,?,?,?,?,?,?,0,'PENDING','',?,?,?,?)", new String[]{"id"});
            ps.setLong(1, state.id()); ps.setLong(2, state.authorId()); ps.setString(3, type); ps.setString(4, title); ps.setLong(5, generation); ps.setString(6, snapshotJson);
            ps.setLong(7, snapshotVersion); ps.setString(8, "RECHECK".equals(kind) ? "HIGH" : "SENSITIVE"); ps.setString(9, ReviewText.cut(words, 400)); ps.setTimestamp(10, Timestamp.from(Instant.now()));
            ps.setString(11, kind); ps.setString(12, level); ps.setLong(13, flowId);
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 使该内容的待审任务失效，并把对应进行中的流转置失效、追加结果事件。 */
    private int invalidatePending(long stateId, Long exceptTask, String reason) {
        String sql = "select id,flow_id from review_submissions where state_id=? and status='PENDING'" + (exceptTask == null ? "" : " and id<>?");
        List<Map<String, Object>> rows = exceptTask == null ? jdbc.queryForList(sql, stateId) : jdbc.queryForList(sql, stateId, exceptTask);
        Timestamp now = Timestamp.from(Instant.now());
        for (Map<String, Object> row : rows) {
            Map<String, Object> lower = new HashMap<>();
            row.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
            jdbc.update("update review_submissions set status='INVALID',result_note=?,closed_at=? where id=?", reason, now, ((Number) lower.get("id")).longValue());
            if (lower.get("flow_id") != null) {
                long flowId = ((Number) lower.get("flow_id")).longValue();
                if (jdbc.update("update review_flows set status='INVALID' where id=? and status in ('IN_REVIEW','AUTO_REJECTED')", flowId) > 0) event(flowId, "RESULT", "提交已失效", reason, "", null, null);
            }
        }
        return rows.size();
    }

    // ------------------------------------------------------------------ 其他内部

    private AccessContext requireReviewer(long operatorId) {
        AccessContext operator = access.forUser(operatorId);
        if (operator == null || !operator.active() || !operator.has(Permission.CONTENT_REVIEW))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "你没有内容审批权限；用户管理权限不能代替审批权限。");
        return operator;
    }

    private void sweep() {
        List<Long> ids = jdbc.queryForList("select id from review_submissions where status='PENDING' and author_id in (select id from user_accounts where account_status<>'ACTIVE')", Long.class);
        for (Long id : ids) {
            Map<String, Object> task = loadTask(id);
            jdbc.update("update review_submissions set status='INVALID',result_note='提交已失效，不能通过旧审批重新公开。',closed_at=? where id=?", Timestamp.from(Instant.now()), id);
            if (task.get("flow_id") != null) {
                long flowId = ((Number) task.get("flow_id")).longValue();
                if (jdbc.update("update review_flows set status='INVALID' where id=? and status in ('IN_REVIEW','AUTO_REJECTED')", flowId) > 0) event(flowId, "RESULT", "提交已失效", "作者账号已停用，旧提交不能再公开。", "", null, null);
            }
        }
    }

    private boolean authorActive(long authorId) {
        Integer n = jdbc.queryForObject("select count(*) from user_accounts where id=? and account_status='ACTIVE'", Integer.class, authorId);
        return n != null && n > 0;
    }

    private boolean otherReviewerAvailable(long authorId) {
        Integer n = jdbc.queryForObject("select count(*) from user_accounts u join role_permissions rp on rp.role_id=u.role_id and rp.permission='content.review' where u.account_status='ACTIVE' and u.id<>?", Integer.class, authorId);
        return n != null && n > 0;
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.time.LocalDateTime t) return t.atZone(ZoneId.systemDefault()).toInstant();
        if (value instanceof java.time.OffsetDateTime t) return t.toInstant();
        return Instant.parse(String.valueOf(value));
    }

    private long count(String sql) { Long n = jdbc.queryForObject(sql, Long.class); return n == null ? 0 : n; }
    private String authorName(long id) { List<String> n = jdbc.queryForList("select display_name from user_accounts where id=?", String.class, id); return n.isEmpty() ? "" : n.get(0); }
    private static String cut(String v, int max) { return ReviewText.cut(v, max); }
    private static String cleanKey(String key) { String k = key == null ? "" : key.trim(); return k.isEmpty() ? UUID.randomUUID().toString() : ReviewText.cut(k, 64); }
    private static ApiProblem takenDown() { return new ApiProblem(HttpStatus.CONFLICT, "内容已下架，保存或发布不能解除下架，请等待人工复核恢复。", "TAKEN_DOWN", null); }

    private Map<String, Object> taskSummary(long id, String type, String title, long authorId, String authorName, String kind, String level, String matched, String status, String result, String operator,
                                            Instant createdAt, Instant closedAt, long generation, long version, long viewerId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("type", type); row.put("title", title); row.put("authorId", authorId); row.put("authorName", authorName);
        row.put("kind", kind); row.put("level", level); row.put("matches", matched == null || matched.isEmpty() ? List.of() : Arrays.asList(matched.split("、")));
        row.put("status", status); row.put("result", result); row.put("operator", operator); row.put("createdAt", createdAt); row.put("closedAt", closedAt);
        row.put("generation", generation); row.put("snapshotVersion", version); row.put("self", authorId == viewerId);
        return row;
    }

    private Map<String, Object> auditRow(long id, String type, String outcome, String operator, String contentType, Long contentId, Long submissionId, Long version, String triggers, String note, String result, Instant at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("type", type); row.put("outcome", outcome); row.put("operator", operator); row.put("contentType", contentType); row.put("contentId", contentId);
        row.put("submissionId", submissionId); row.put("snapshotVersion", version); row.put("triggers", triggers); row.put("note", note); row.put("result", result); row.put("at", at);
        return row;
    }

    private List<Map<String, Object>> history(Long submissionId, String type, Long targetId) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("select id,type,outcome,operator_name,content_type,content_id,submission_id,snapshot_version,triggers,note,result_message,created_at from review_audit_log where type not in ('RULE_CREATE','RULE_UPDATE','RULE_DELETE','BATCH_RULES','IMPORT_RULES') and (1=0");
        if (submissionId != null) { sql.append(" or submission_id=?"); args.add(submissionId); }
        if (targetId != null) { sql.append(" or (content_type=? and content_id=?)"); args.add(type); args.add(targetId); }
        sql.append(") order by created_at desc,id desc limit 30");
        return jdbc.query(sql.toString(), (rs, n) -> auditRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), (Long) rs.getObject(6), (Long) rs.getObject(7), (Long) rs.getObject(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getTimestamp(12).toInstant()), args.toArray());
    }

    private Map<String, Object> loadTask(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from review_submissions where id=?", id);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "未找到这条审批提交，请返回列表核对。");
        Map<String, Object> raw = rows.get(0), row = new HashMap<>();
        raw.forEach((k, v) -> row.put(k.toLowerCase(Locale.ROOT), v));
        return row;
    }

    private void saveState(State state, long generation, Long targetId, boolean approvedOnce) {
        jdbc.update("update review_content_state set attempts=0,generation=?,target_id=?,approved_once=?,updated_at=? where id=?", generation, targetId, approvedOnce, Timestamp.from(Instant.now()), state.id());
    }

    private State lockState(long authorId, String type, String key, boolean approvedOnce, Long target) {
        independent.executeWithoutResult(status -> {
            Integer exists = jdbc.queryForObject("select count(*) from review_content_state where author_id=? and content_type=? and content_key=?", Integer.class, authorId, type, key);
            if (exists != null && exists > 0) return;
            Timestamp now = Timestamp.from(Instant.now());
            try {
                jdbc.update("insert into review_content_state(author_id,content_type,content_key,target_id,attempts,generation,approved_once,legacy,created_at,updated_at) values(?,?,?,?,0,1,?,?,?,?)",
                        authorId, type, key, target, approvedOnce, approvedOnce && COLLECTION.equals(type), now, now);
            } catch (DataIntegrityViolationException ignored) { /* 并发创建：另一事务已建立，下面按键重读 */ }
        });
        Long id = jdbc.queryForObject("select id from review_content_state where author_id=? and content_type=? and content_key=?", Long.class, authorId, type, key);
        return lockStateById(Objects.requireNonNull(id));
    }

    private State lockStateForTarget(String type, long targetId, long authorId) {
        String key = (COLLECTION.equals(type) ? "COLLECTION:" : "ID:") + targetId;
        List<Long> found = jdbc.queryForList("select id from review_content_state where content_type=? and target_id=?", Long.class, type, targetId);
        if (!found.isEmpty()) return lockStateById(found.get(0));
        independent.executeWithoutResult(status -> {
            Integer exists = jdbc.queryForObject("select count(*) from review_content_state where author_id=? and content_type=? and content_key=?", Integer.class, authorId, type, key);
            if (exists != null && exists > 0) return;
            Timestamp now = Timestamp.from(Instant.now());
            try {
                jdbc.update("insert into review_content_state(author_id,content_type,content_key,target_id,attempts,generation,approved_once,legacy,created_at,updated_at) values(?,?,?,?,0,1,true,true,?,?)", authorId, type, key, targetId, now, now);
            } catch (DataIntegrityViolationException ignored) { /* 并发创建 */ }
        });
        return lockState(authorId, type, key, true, targetId);
    }

    private State stateById(long id) { return mapState("select id,author_id,content_type,target_id,generation,approved_once,legacy from review_content_state where id=?", id); }
    private State lockStateById(long id) { return mapState("select id,author_id,content_type,target_id,generation,approved_once,legacy from review_content_state where id=? for update", id); }
    private State mapState(String sql, long id) {
        List<State> rows = jdbc.query(sql, (rs, n) -> new State(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long) rs.getObject(4), rs.getLong(5), rs.getBoolean(6), rs.getBoolean(7)), id);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "内容不存在。");
        return rows.get(0);
    }

    private record Published(long authorId, String moderation, List<String> texts) {}

    private static String tableOf(String type) { return switch (type) { case ARTICLE -> "content_posts"; case COMMENT -> "comments"; default -> "community_collections"; }; }

    private Published publishedOf(String type, long id) {
        switch (type) {
            case ARTICLE -> {
                List<Published> rows = jdbc.query("select author_id,moderation_state,title,summary,body from content_posts where id=? and type='ARTICLE'", (rs, n) -> new Published(rs.getLong(1), rs.getString(2), List.of(rs.getString(3), rs.getString(4), rs.getString(5))), id);
                if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "未找到可处置的发布版。");
                return rows.get(0);
            }
            case COMMENT -> {
                List<Published> rows = jdbc.query("select author_id,moderation_state,body from comments where id=?", (rs, n) -> new Published(rs.getLong(1), rs.getString(2), List.of(rs.getString(3))), id);
                if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "未找到可处置的发布版。");
                return rows.get(0);
            }
            case COLLECTION -> {
                List<String[]> rows = jdbc.query("select c.owner_id,c.moderation_state,p.document from community_collections c join collection_publications p on p.collection_id=c.id where c.id=? and c.status='PUBLISHED'",
                        (rs, n) -> new String[]{String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3)}, id);
                if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "未找到可处置的发布版。");
                try {
                    return new Published(Long.parseLong(rows.get(0)[0]), rows.get(0)[1], outlineTexts(json.readValue(rows.get(0)[2], PublicOutline.class)));
                } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read publication", e); }
            }
            default -> throw new ApiProblem(HttpStatus.BAD_REQUEST, "内容类型无效。");
        }
    }

    private Object publishedView(String type, long id) {
        Map<String, Object> view = new LinkedHashMap<>();
        switch (type) {
            case ARTICLE -> jdbc.query("select title,summary,body,moderation_state from content_posts where id=?", rs -> {
                view.put("title", rs.getString(1)); view.put("summary", rs.getString(2)); view.put("body", rs.getString(3)); view.put("moderation", rs.getString(4));
            }, id);
            case COMMENT -> jdbc.query("select body,moderation_state,post_id from comments where id=?", rs -> {
                view.put("body", rs.getString(1)); view.put("moderation", rs.getString(2)); view.put("postId", rs.getLong(3));
            }, id);
            default -> jdbc.query("select p.document,p.visibility,c.moderation_state,c.name,c.description from collection_publications p join community_collections c on c.id=p.collection_id where p.collection_id=? and c.status='PUBLISHED'", rs -> {
                view.put("outline", parse(rs.getString(1))); view.put("visibility", rs.getString(2)); view.put("moderation", rs.getString(3)); view.put("name", rs.getString(4)); view.put("description", rs.getString(5));
            }, id);
        }
        return view.isEmpty() ? null : view;
    }

    static List<String> outlineTexts(PublicOutline outline) {
        List<String> texts = new ArrayList<>();
        texts.add(outline.collection().name()); texts.add(outline.collection().description());
        texts.add(outline.collection().audience()); texts.add(outline.collection().goal());
        if (outline.metadata()!=null) { texts.add(outline.metadata().seoTitle());texts.addAll(outline.metadata().tags());texts.addAll(outline.metadata().audiences()); }
        for (PublicSection section : outline.sections()) {
            texts.add(section.title()); texts.add(section.description());
            for (PublicEntry entry : section.entries()) { texts.add(entry.title()); texts.add(entry.annotation()); texts.add(entry.body()); }
        }
        texts.removeIf(Objects::isNull);
        return texts;
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private SubmitResult fromJson(String value) {
        try { return json.readValue(value, SubmitResult.class); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private JsonNode parse(String value) {
        try { return json.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
