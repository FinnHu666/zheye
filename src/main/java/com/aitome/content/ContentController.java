package com.aitome.content;

import com.aitome.access.Permission;
import com.aitome.access.RequirePermission;
import com.aitome.access.PublicEndpoint;
import com.aitome.access.AccessService;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.review.ReviewService;
import com.aitome.review.ReviewService.SubmitResult;
import com.aitome.user.AuthController;
import com.aitome.user.UserAccount;
import com.aitome.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ContentController {
    private final ContentPostRepository posts;
    private final CommentRepository comments;
    private final CollectionRepository collections;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final CollectionPublicationService publications;
    private final AccessService access;
    private final ReviewService review;

    public ContentController(ContentPostRepository posts, CommentRepository comments, CollectionRepository collections, UserRepository users, JdbcTemplate jdbc, CollectionPublicationService publications, AccessService access, ReviewService review) {
        this.posts = posts; this.comments = comments; this.collections = collections; this.users = users; this.jdbc = jdbc; this.publications = publications; this.access = access; this.review = review;
    }

    @GetMapping("/posts") @PublicEndpoint("公开内容") 
    public PostPage posts(@RequestParam(required = false) ContentPost.Type type,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, Math.min(page, 10_000));
        int safeSize = Math.max(1, Math.min(size, 50));
        PageRequest request = PageRequest.of(safePage, safeSize);
        Page<ContentPostSummary> result = type == null
                ? posts.findAllSummaries(request)
                : posts.findSummariesByType(type, request);
        List<ContentPostSummary> items = result.getContent();
        return new PostPage(items, safePage, safeSize, result.getTotalElements());
    }

    @GetMapping("/posts/{id}") @PublicEndpoint("公开内容") public ContentPost post(@PathVariable Long id) { return requirePost(id); }

    @PostMapping("/posts") @RequirePermission(Permission.POST_CREATE)
    public ResponseEntity<Object> create(@Valid @RequestBody PostRequest request, HttpSession session) {
        UserAccount user = AuthController.requireUser(session, users);
        // 普通话题是否纳入审核由 F02 另行评审，保持直接发布；文章经敏感检测（FINNS-17）
        if (request.type() == ContentPost.Type.TOPIC)
            return ResponseEntity.status(HttpStatus.CREATED).body(posts.save(new ContentPost(request.type(), request.title(), request.summary(), request.body(), user.getId(), user.getDisplayName())));
        SubmitResult result = review.submitArticle(user.getId(), user.getDisplayName(), request.draftKey(), request.requestId(), request.title(), request.summary(), request.body());
        return respond(result, () -> posts.findById(result.targetId()).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "内容不存在")), HttpStatus.CREATED);
    }

    /** 把提交结果转成响应：已发布 → 原有成功响应；送审/自动不通过 → 202（outcome 区分）。不抛异常，避免回滚流转记录。 */
    public static ResponseEntity<Object> respond(SubmitResult result, java.util.function.Supplier<Object> published, HttpStatus successStatus) {
        if ("PUBLISHED".equals(result.outcome())) return ResponseEntity.status(successStatus).body(published.get());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 202);
        body.put("message", result.message());
        body.put("outcome", result.outcome());
        body.put("flowId", result.flowId());
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/posts/{id}/like") @Transactional @RequirePermission(Permission.POST_INTERACT)
    public ContentPost like(@PathVariable Long id) { ContentPost post = requirePost(id); post.like(); return post; }

    @GetMapping("/posts/{id}/comments") @PublicEndpoint("公开内容")
    public CommentPage comments(@PathVariable Long id,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        requirePost(id);
        int safePage = Math.max(0, Math.min(page, 10_000));
        int safeSize = Math.max(1, Math.min(size, 50));
        List<Comment> items = comments.findByPostIdAndModerationStateOrderByCreatedAtAscIdAsc(id, "VISIBLE", PageRequest.of(safePage, safeSize));
        return new CommentPage(items, safePage, safeSize, comments.countByPostIdAndModerationState(id, "VISIBLE"));
    }

    @PostMapping("/posts/{id}/comments") @RequirePermission(Permission.POST_INTERACT)
    public ResponseEntity<Object> comment(@PathVariable Long id, @Valid @RequestBody CommentRequest request, HttpSession session) {
        ContentPost post = requirePost(id);
        UserAccount user = AuthController.requireUser(session, users);
        SubmitResult result = review.submitComment(user.getId(), user.getDisplayName(), id, request.draftKey(), request.requestId(), post.getTitle(), request.body());
        return respond(result, () -> comments.findById(result.targetId()).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "评论不存在")), HttpStatus.CREATED);
    }

    @GetMapping("/collections") @PublicEndpoint("公开专题；受限专题按权限过滤")
    public CollectionPage collections(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size, jakarta.servlet.http.HttpServletRequest request) {
        boolean restricted = access.current(request).has(com.aitome.access.Permission.CONTENT_RESTRICTED);
        int safePage = Math.max(0, Math.min(page, 10_000));
        int safeSize = Math.max(1, Math.min(size, 50));
        long offset = (long) safePage * safeSize;
        List<CollectionView> items = jdbc.query(
                "select c.id,p.name,p.description,p.owner_name,p.updated_at,p.item_count " +
                        "from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED' and c.moderation_state='VISIBLE' and (p.visibility='PUBLIC' or ?) order by p.updated_at desc,c.id desc limit ? offset ?",
                (rs, row) -> new CollectionView(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getString("owner_name"), rs.getInt("item_count"), rs.getTimestamp("updated_at").toInstant()),
                restricted, safeSize, offset);
        Long total = jdbc.queryForObject("select count(*) from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED' and c.moderation_state='VISIBLE' and (p.visibility='PUBLIC' or ?)", Long.class, restricted);
        return new CollectionPage(items, safePage, safeSize, total == null ? 0 : total);
    }

    @GetMapping("/collections/{id}") @PublicEndpoint("公开专题；受限专题按权限拒绝")
    public CollectionView collectionDetail(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        var outline = publications.read(id, access.current(request));
        var c = outline.collection();
        return new CollectionView(id,c.name(),c.description(),c.ownerName(),outline.sections().stream().mapToInt(s -> s.entries().size()).sum(),c.updatedAt());
    }

    @GetMapping("/collections/{id}/posts") @PublicEndpoint("公开专题；受限专题按权限拒绝")
    public CollectionPostsPage collectionPosts(@PathVariable Long id,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size, jakarta.servlet.http.HttpServletRequest request) {
        var outline = publications.read(id, access.current(request));
        int safePage = Math.max(0, Math.min(page, 10_000));
        int safeSize = Math.max(1, Math.min(size, 50));
        List<CollectionWorkspaceController.PublicEntry> entries = outline.sections().stream().flatMap(s -> s.entries().stream())
            .filter(e -> e.kind() == CollectionEntry.Kind.POST && e.postId() != null).toList();
        int start = Math.min(entries.size(), safePage * safeSize);
        List<ContentPostSummary> items = entries.subList(start, Math.min(entries.size(),start+safeSize)).stream().map(e -> {
            List<ContentPostSummary> summaries = jdbc.query("select id,type,created_at,likes from content_posts where id=? and moderation_state='VISIBLE'", (rs,n) -> new ContentPostSummary(rs.getLong("id"),ContentPost.Type.valueOf(rs.getString("type")),e.postTitle(),e.postSummary(),e.postAuthor(),rs.getTimestamp("created_at").toInstant(),rs.getInt("likes")),e.postId());
            return summaries.isEmpty() ? null : summaries.get(0);
        }).filter(java.util.Objects::nonNull).toList();
        return new CollectionPostsPage(items,safePage,safeSize,entries.size());
    }

    @PostMapping("/collections") @ResponseStatus(HttpStatus.CREATED) @Transactional @RequirePermission(Permission.COLLECTION_MANAGE)
    public CollectionView collection(@Valid @RequestBody CollectionRequest request, HttpSession session) {
        UserAccount user = AuthController.requireUser(session, users);
        CommunityCollection saved = collections.save(new CommunityCollection(request.name(), request.description(), user.getDisplayName(), user.getId(), CommunityCollection.Format.CUSTOM, CommunityCollection.Status.DRAFT, "", ""));
        jdbc.update("insert into collection_sections(collection_id,title,description,sort_order,created_at,updated_at) values(?,'专题内容','',0,current_timestamp,current_timestamp)", saved.getId());
        return new CollectionView(saved.getId(), saved.getName(), saved.getDescription(), saved.getOwnerName(), 0, saved.getUpdatedAt());
    }

    private ContentPost requirePost(Long id) { return posts.findById(id).filter(p -> "VISIBLE".equals(p.getModerationState())).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "内容不存在")); }

    public record PostRequest(ContentPost.Type type, @NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 280) String summary, @NotBlank @Size(max = 20000) String body, @Size(max = 64) String requestId, @Size(max = 64) String draftKey) {}
    public record CommentRequest(@NotBlank @Size(max = 1000) String body, @Size(max = 64) String requestId, @Size(max = 64) String draftKey) {}
    public record CommentPage(List<Comment> items, int page, int size, long total) {}
    public record PostPage(List<ContentPostSummary> items, int page, int size, long total) {}
    public record CollectionPostsPage(List<ContentPostSummary> items, int page, int size, long total) {}
    public record CollectionRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 260) String description) {}
    public record CollectionView(Long id, String name, String description, String ownerName, int itemCount, java.time.Instant updatedAt) {}
    public record CollectionPage(List<CollectionView> items, int page, int size, long total) {}
}
