package com.aitome.content;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.user.AuthController;
import com.aitome.user.UserAccount;
import com.aitome.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;

@RestController
public class CollectionWorkspaceController {
    private static final int MAX_SECTIONS = 50;
    private static final int MAX_ENTRIES = 500;
    private final CollectionRepository collections;
    private final ContentPostRepository posts;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final CollectionPublicationService publications;

    public CollectionWorkspaceController(CollectionRepository collections, ContentPostRepository posts, UserRepository users, JdbcTemplate jdbc, CollectionPublicationService publications) {
        this.collections = collections;
        this.posts = posts;
        this.users = users;
        this.jdbc = jdbc;
        this.publications = publications;
    }

    @PostMapping("/api/me/collections/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DraftDocument createDraft(@Valid @RequestBody CreateDraftRequest request, HttpSession session) {
        UserAccount user = currentUser(session);
        CommunityCollection saved = collections.save(new CommunityCollection(
                cleanRequired(request.name(), "专题名称不能为空"),
                cleanOptional(request.description()),
                user.getDisplayName(), user.getId(), request.format(), CommunityCollection.Status.DRAFT,
                cleanOptional(request.audience()), cleanOptional(request.goal())));
        List<String> titles = templateSections(request.format());
        if (request.templateKey() != null && !request.templateKey().isBlank()) {
            titles = CollectionTemplates.sections(request.templateKey());
        }
        Long firstSection = null;
        for (String title : titles) {
            long sectionId = insertSection(saved.getId(), title, "", nextSectionOrder(saved.getId()));
            if (firstSection == null) firstSection = sectionId;
        }
        if (!blank(request.initialContent())) {
            insertEntry(saved.getId(), firstSection, new EntryInput(null, CollectionEntry.Kind.NOTE, "", "", request.initialContent(), null, ""), 0);
        }
        return draft(saved.getId(), user.getId());
    }

    @GetMapping("/api/me/collections/{id}/draft")
    public DraftDocument draft(@PathVariable long id, HttpSession session) {
        return draft(id, currentUser(session).getId());
    }

    @PutMapping("/api/me/collections/{id}/draft")
    @Transactional
    public DraftDocument saveDraft(@PathVariable long id, @Valid @RequestBody SaveDraftRequest request, HttpSession session) {
        UserAccount user = currentUser(session);
        return saveDocument(id, request, user.getId());
    }

    public DraftDocument saveDocument(long id, SaveDraftRequest request, long userId) {
        OwnedCollection owned = owned(id, userId);
        validateDocument(request, userId);
        int updated = jdbc.update("update community_collections set name=?,description=?,format=?,audience=?,goal=?,updated_at=current_timestamp,revision=revision+1 where id=? and owner_id=? and revision=?",
                request.name().trim(), cleanOptional(request.description()), request.format().name(), cleanOptional(request.audience()), cleanOptional(request.goal()),
                id, userId, request.revision());
        if (updated == 0) throw new ApiProblem(HttpStatus.CONFLICT, "专题已在其他页面更新，请刷新后重试（当前版本 " + owned.revision() + "）");

        jdbc.update("delete from collection_entries where collection_id=?", id);
        jdbc.update("delete from collection_sections where collection_id=?", id);
        int sectionOrder = 0;
        for (SectionInput section : request.sections()) {
            long sectionId = insertSection(id, section.title().trim(), cleanOptional(section.description()), sectionOrder++);
            int entryOrder = 0;
            for (EntryInput entry : section.entries()) insertEntry(id, sectionId, entry, entryOrder++);
        }
        return draft(id, userId);
    }

    @PostMapping("/api/me/collections/{id}/publish")
    @Transactional
    public DraftDocument publish(@PathVariable long id, @Valid @RequestBody VersionRequest request, HttpSession session) {
        UserAccount user = currentUser(session);
        OwnedCollection row = owned(id, user.getId());
        jdbc.queryForObject("select id from community_collections where id=? for update", Long.class, id);
        DraftDocument document = draft(id, user.getId());
        List<String> problems = publishProblems(document);
        if (!problems.isEmpty()) throw bad(String.join("；", problems));
        Integer sectionCount = jdbc.queryForObject("select count(*) from collection_sections where collection_id=?", Integer.class, id);
        Integer entryCount = jdbc.queryForObject("select count(*) from collection_entries where collection_id=?", Integer.class, id);
        if (sectionCount == null || sectionCount == 0 || entryCount == null || entryCount == 0)
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "发布前至少需要一个章节和一个有效条目");
        int updated = jdbc.update("update community_collections set status='PUBLISHED',published_at=current_timestamp,updated_at=current_timestamp,revision=revision+1 where id=? and owner_id=? and revision=?",
                id, user.getId(), request.revision());
        if (updated == 0) throw new ApiProblem(HttpStatus.CONFLICT, "专题版本已变化，请刷新后重试（当前版本 " + row.revision() + "）");
        publications.publishSnapshot(id);
        return draft(id, user.getId());
    }

    @PostMapping("/api/me/collections/{id}/archive")
    @Transactional
    public DraftDocument archive(@PathVariable long id, @Valid @RequestBody VersionRequest request, HttpSession session) {
        UserAccount user = currentUser(session);
        OwnedCollection row = owned(id, user.getId());
        int updated = jdbc.update("update community_collections set status='ARCHIVED',updated_at=current_timestamp,revision=revision+1 where id=? and owner_id=? and revision=?",
                id, user.getId(), request.revision());
        if (updated == 0) throw new ApiProblem(HttpStatus.CONFLICT, "专题版本已变化，请刷新后重试（当前版本 " + row.revision() + "）");
        jdbc.update("delete from user_collection_selections where collection_id=?", id);
        return draft(id, user.getId());
    }

    @GetMapping("/api/collections/{id}/outline")
    public PublicOutline outline(@PathVariable long id) {
        return publications.read(id);
    }

    @GetMapping("/api/me/collections/{id}/preview")
    public PublicOutline preview(@PathVariable long id, HttpSession session) {
        owned(id, currentUser(session).getId());
        return publications.capture(id);
    }

    public List<String> publishProblems(DraftDocument document) {
        List<String> problems = new ArrayList<>();
        if (document.sections().isEmpty()) problems.add("至少添加一个章节");
        int count = 0;
        for (SectionView section : document.sections()) {
            for (EntryView entry : section.entries()) {
                count++;
                String at = section.title() + " / 第 " + (entry.sortOrder() + 1) + " 条";
                if (entry.kind() == CollectionEntry.Kind.NOTE && blank(entry.body())) problems.add(at + "：补充正文，或删除这条空内容");
                if (entry.kind() == CollectionEntry.Kind.LINK) {
                    if (blank(entry.title())) problems.add(at + "：补充链接标题");
                    try { validateUrl(entry.externalUrl()); } catch (ApiProblem e) { problems.add(at + "：填写有效 http/https 链接"); }
                }
                if (entry.kind() == CollectionEntry.Kind.POST && (entry.postId() == null || blank(entry.postTitle()))) problems.add(at + "：选择可用文章");
            }
        }
        if (count == 0) problems.add("至少添加一个有效条目");
        return problems;
    }

    public DraftDocument draft(long id, long userId) {
        OwnedCollection row = owned(id, userId);
        List<SectionView> sections = sectionViews(id, true);
        return new DraftDocument(row.id(), row.name(), row.description(), row.format(), row.status(), row.audience(), row.goal(),
                row.ownerName(), row.revision(), row.updatedAt(), row.publishedAt(), sections);
    }

    private OwnedCollection owned(long id, long userId) {
        List<OwnedCollection> rows = jdbc.query("select id,name,description,owner_id,owner_name,format,status,audience,goal,revision,updated_at,published_at from community_collections where id=?",
                (rs, n) -> new OwnedCollection(rs.getLong("id"), rs.getString("name"), rs.getString("description"), (Long) rs.getObject("owner_id"),
                        rs.getString("owner_name"), CommunityCollection.Format.valueOf(rs.getString("format")), CommunityCollection.Status.valueOf(rs.getString("status")),
                        rs.getString("audience"), rs.getString("goal"), rs.getLong("revision"), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant()), id);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "专题不存在");
        OwnedCollection row = rows.get(0);
        if (!Objects.equals(row.ownerId(), userId)) throw new ApiProblem(HttpStatus.FORBIDDEN, "只能管理自己创建的专题");
        return row;
    }

    private List<SectionView> sectionViews(long collectionId, boolean draft) {
        return jdbc.query("select id,title,description,sort_order from collection_sections where collection_id=? order by sort_order,id",
                (rs, n) -> new SectionView(rs.getLong("id"), rs.getString("title"), rs.getString("description"), rs.getInt("sort_order"),
                        entryViews(collectionId, rs.getLong("id"), draft)), collectionId);
    }

    private List<EntryView> entryViews(long collectionId, long sectionId, boolean draft) {
        return jdbc.query("select e.id,e.kind,e.sort_order,e.title,e.annotation,e.body,e.post_id,e.external_url,p.title as post_title " +
                        "from collection_entries e left join content_posts p on p.id=e.post_id where e.collection_id=? and e.section_id=? order by e.sort_order,e.id",
                (rs, n) -> new EntryView(rs.getLong("id"), CollectionEntry.Kind.valueOf(rs.getString("kind")), rs.getInt("sort_order"),
                        rs.getString("title"), rs.getString("annotation"), rs.getString("body"), (Long) rs.getObject("post_id"),
                        rs.getString("post_title"), rs.getString("external_url")), collectionId, sectionId);
    }

    public void validateDocument(SaveDraftRequest request, long userId) {
        if (request == null) throw bad("专题文档不能为空");
        if (request.name() == null || request.name().trim().isEmpty() || request.name().trim().length() > 80) throw bad("专题名称需要 1–80 个字符");
        if (request.description() != null && request.description().length() > 260) throw bad("专题简介最多 260 个字符");
        if (request.audience() != null && request.audience().trim().length() > 160) throw bad("目标读者最多 160 个字符");
        if (request.goal() != null && request.goal().trim().length() > 300) throw bad("专题目标最多 300 个字符");
        if (request.format() == null) throw bad("选择专题结构");
        if (request.sections() == null || request.sections().size() > MAX_SECTIONS) throw bad("专题最多包含 50 个章节");
        int total = 0;
        Set<Long> postIds = new HashSet<>();
        for (SectionInput section : request.sections()) {
            if (section == null || section.title() == null || section.title().trim().isEmpty() || section.title().trim().length() > 80) throw bad("章节标题需要 1–80 个字符");
            if (section.description() != null && section.description().trim().length() > 500) throw bad("章节说明最多 500 个字符");
            if (section.entries() == null) throw bad("章节条目不能为空");
            total += section.entries().size();
            if (total > MAX_ENTRIES) throw bad("专题最多包含 500 个条目");
            for (EntryInput entry : section.entries()) validateEntry(entry, userId, postIds);
        }
    }

    private void validateEntry(EntryInput entry, long userId, Set<Long> postIds) {
        if (entry == null || entry.kind() == null) throw bad("条目类型不能为空");
        if (entry.title() != null && entry.title().length() > 160) throw bad("条目标题最多 160 个字符");
        if (entry.body() != null && entry.body().length() > 4000) throw bad("笔记正文最多 4000 个字符");
        if (entry.externalUrl() != null && entry.externalUrl().length() > 2000) throw bad("链接最多 2000 个字符");
        if (entry.annotation() != null && entry.annotation().trim().length() > 1000) throw bad("条目说明最多 1000 个字符");
        switch (entry.kind()) {
            case NOTE -> {
                
                
            }
            case LINK -> {
                if (!blank(entry.externalUrl())) validateUrl(entry.externalUrl());
            }
            case POST -> {
                if (entry.postId() == null) break;
                if (!postIds.add(entry.postId())) throw bad("站内文章不能为空且不能重复收录");
                ContentPost post = posts.findById(entry.postId()).orElseThrow(() -> bad("所选站内内容不存在"));
                if (!Objects.equals(post.getAuthorId(), userId)) throw new ApiProblem(HttpStatus.FORBIDDEN, "专题只能引用自己发布的站内内容");
            }
        }
    }

    private void validateUrl(String value) {
        if (blank(value) || value.trim().length() > 2000) throw bad("外部链接需要有效的 http/https 地址");
        try {
            URI uri = new URI(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || blank(uri.getHost())) throw bad("外部链接只允许 http/https 地址");
        } catch (URISyntaxException error) { throw bad("外部链接格式不正确"); }
    }

    private long insertSection(long collectionId, String title, String description, int order) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("insert into collection_sections(collection_id,title,description,sort_order,created_at,updated_at) values(?,?,?,?,current_timestamp,current_timestamp)", new String[]{"id"});
            statement.setLong(1, collectionId); statement.setString(2, title); statement.setString(3, description); statement.setInt(4, order);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("专题章节创建失败");
        return key.longValue();
    }

    private void insertEntry(long collectionId, long sectionId, EntryInput entry, int order) {
        String title = entry.kind() == CollectionEntry.Kind.POST ? cleanNullable(entry.title()) : cleanOptional(entry.title());
        String body = entry.kind() == CollectionEntry.Kind.NOTE ? (entry.body() == null ? "" : entry.body()) : null;
        Long postId = entry.kind() == CollectionEntry.Kind.POST ? entry.postId() : null;
        String url = entry.kind() == CollectionEntry.Kind.LINK ? cleanOptional(entry.externalUrl()) : null;
        jdbc.update("insert into collection_entries(collection_id,section_id,kind,sort_order,title,annotation,body,post_id,external_url,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,current_timestamp,current_timestamp)",
                collectionId, sectionId, entry.kind().name(), order, title, cleanOptional(entry.annotation()), body, postId, url);
    }

    private int nextSectionOrder(long collectionId) {
        Integer next = jdbc.queryForObject("select coalesce(max(sort_order),-1)+1 from collection_sections where collection_id=?", Integer.class, collectionId);
        return next == null ? 0 : next;
    }

    private List<String> templateSections(CommunityCollection.Format format) {
        return switch (format) {
            case GUIDE -> List.of("准备", "执行", "检查", "复盘");
            case READING_PATH -> List.of("入门", "主线", "扩展", "回顾");
            case RESOURCE_LIST -> List.of("必备", "按场景选择", "补充资料");
            case CUSTOM -> List.of();
        };
    }

    private UserAccount currentUser(HttpSession session) { return AuthController.requireUser(session, users); }
    private ApiProblem bad(String message) { return new ApiProblem(HttpStatus.BAD_REQUEST, message); }
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private String cleanRequired(String value, String message) { if (blank(value)) throw bad(message); return value.trim(); }
    private String cleanOptional(String value) { return value == null ? "" : value.trim(); }
    private String cleanNullable(String value) { return blank(value) ? null : value.trim(); }

    public record CreateDraftRequest(@NotBlank @Size(max = 80) String name,
                                     @Size(max = 260) String description,
                                     @NotNull CommunityCollection.Format format,
                                     @Size(max = 160) String audience,
                                     @Size(max = 300) String goal, @Size(max = 40) String templateKey,
                                     @Size(max = 4000) String initialContent) {}
    public record SaveDraftRequest(long revision, @NotBlank @Size(max = 80) String name,
                                   @Size(max = 260) String description,
                                   @NotNull CommunityCollection.Format format,
                                   @Size(max = 160) String audience, @Size(max = 300) String goal,
                                   @NotNull @Size(max = MAX_SECTIONS) List<SectionInput> sections) {}
    public record SectionInput(Long id, @NotBlank @Size(max = 80) String title, @Size(max = 500) String description,
                               @NotNull List<EntryInput> entries) {}
    public record EntryInput(Long id, @NotNull CollectionEntry.Kind kind, @Size(max = 160) String title,
                             @Size(max = 1000) String annotation, @Size(max = 4000) String body,
                             Long postId, @Size(max = 2000) String externalUrl) {}
    public record VersionRequest(long revision) {}
    private record OwnedCollection(long id, String name, String description, Long ownerId, String ownerName,
                                   CommunityCollection.Format format, CommunityCollection.Status status, String audience,
                                   String goal, long revision, Instant updatedAt, Instant publishedAt) {}
    public record DraftDocument(long id, String name, String description, CommunityCollection.Format format,
                                CommunityCollection.Status status, String audience, String goal, String ownerName,
                                long revision, Instant updatedAt, Instant publishedAt, List<SectionView> sections) {}
    public record SectionView(long id, String title, String description, int sortOrder, List<EntryView> entries) {}
    public record EntryView(long id, CollectionEntry.Kind kind, int sortOrder, String title, String annotation,
                            String body, Long postId, String postTitle, String externalUrl) {}
    public record PublicCollection(long id, String name, String description, String ownerName,
                                   CommunityCollection.Format format, String audience, String goal,
                                   Instant updatedAt, Instant publishedAt) {}
    public record PublicOutline(PublicCollection collection, List<PublicSection> sections) {}
    public record PublicSection(long id, String title, String description, int sortOrder, List<PublicEntry> entries) {}
    public record PublicEntry(long id, CollectionEntry.Kind kind, int sortOrder, String title, String annotation,
                              String body, Long postId, String postTitle, String postSummary, String postAuthor,
                              String externalUrl) {}
}
