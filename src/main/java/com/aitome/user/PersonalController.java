package com.aitome.user;

import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.aitome.content.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.Size;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/me")
public class PersonalController {
    private final UserRepository users;
    private final ContentPostRepository posts;
    private final CollectionRepository collections;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public PersonalController(UserRepository users, ContentPostRepository posts, CollectionRepository collections, JdbcTemplate jdbc, EntityManager entityManager) {
        this.users = users; this.posts = posts; this.collections = collections; this.jdbc = jdbc; this.entityManager = entityManager;
    }

    @GetMapping("/profile")
    public ProfileView profile(HttpSession session) { return profileOf(currentUser(session)); }

    @RequestMapping(path = "/profile", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @Transactional
    public ProfileView updateProfile(@Valid @RequestBody ProfileRequest request, HttpSession session) {
        UserAccount user = currentUser(session);
        user.updateProfile(request.displayName().trim(), request.bio() == null ? "" : request.bio().trim());
        return profileOf(user);
    }

    @GetMapping("/posts")
    public PageView<ContentPostSummary> myPosts(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpSession session) {
        int safeSize = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, Math.min(page, 10_000));
        Page<ContentPostSummary> result = posts.findSummariesByAuthorId(currentUser(session).getId(), PageRequest.of(safePage, safeSize));
        return new PageView<>(result.getContent(), safePage, safeSize, result.hasNext());
    }

    @GetMapping("/collections")
    public PageView<CollectionView> myCollections(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpSession session) {
        int safeSize = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, Math.min(page, 10_000));
        List<CollectionView> rows = jdbc.query("select c.id,c.name,c.description,c.owner_name,c.updated_at,c.format,c.status,c.revision,count(ce.id) as item_count from community_collections c left join collection_entries ce on ce.collection_id=c.id where c.owner_id=? group by c.id order by c.updated_at desc,c.id desc limit ? offset ?",
                (rs, row) -> new CollectionView(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getString("owner_name"), rs.getInt("item_count"), rs.getTimestamp("updated_at").toInstant(), rs.getString("format"), rs.getString("status"), rs.getLong("revision")),
                currentUser(session).getId(), safeSize + 1, (long) safePage * safeSize);
        return page(rows, safePage, safeSize);
    }

    @GetMapping("/collections/selected")
    public SelectionView selected(HttpSession session) {
        UserAccount user = currentUser(session);
        return selectionView(user.getId());
    }

    @PutMapping("/collections/selected/{collectionId}")
    @Transactional
    public SelectionView addSelected(@PathVariable long collectionId, HttpSession session) {
        UserAccount user = currentUser(session);
        CommunityCollection selectedCollection = collections.findById(collectionId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "专题不存在"));
        if (selectedCollection.getStatus() != CommunityCollection.Status.PUBLISHED || jdbc.queryForObject("select count(*) from collection_publications where collection_id=?",Integer.class,collectionId)==0) throw new ApiProblem(HttpStatus.NOT_FOUND, "专题不存在");
        long version = lockPreference(user.getId());
        Integer exists = jdbc.queryForObject("select count(*) from user_collection_selections where user_id=? and collection_id=?", Integer.class, user.getId(), collectionId);
        if (exists == null || exists == 0) {
            Integer total = jdbc.queryForObject("select count(*) from user_collection_selections where user_id=?", Integer.class, user.getId());
            if (total != null && total >= 1000) throw new ApiProblem(HttpStatus.BAD_REQUEST, "自选专题最多保存 1000 个");
            Integer max = jdbc.queryForObject("select coalesce(max(sort_order),-1) from user_collection_selections where user_id=?", Integer.class, user.getId());
            jdbc.update("insert into user_collection_selections(user_id,collection_id,sort_order,created_at) values(?,?,?,current_timestamp)", user.getId(), collectionId, max + 1);
            bumpPreference(user.getId(), version);
        }
        return selectionView(user.getId());
    }

    @DeleteMapping("/collections/selected/{collectionId}")
    @Transactional
    public SelectionView removeSelected(@PathVariable long collectionId, HttpSession session) {
        UserAccount user = currentUser(session);
        long version = lockPreference(user.getId());
        List<Long> ids = selectionIds(user.getId());
        if (ids.remove(collectionId)) {
            jdbc.update("delete from user_collection_selections where user_id=? and collection_id=?", user.getId(), collectionId);
            normalizeOrder(user.getId(), ids);
            bumpPreference(user.getId(), version);
        }
        return selectionView(user.getId());
    }

    @PatchMapping("/collections/selected/order")
    @Transactional
    public SelectionView reorder(@Valid @RequestBody ReorderRequest request, HttpSession session) {
        UserAccount user = currentUser(session);
        long currentVersion = lockPreference(user.getId());
        if (request.version() != currentVersion) throw new ApiProblem(HttpStatus.CONFLICT, "专题列表已在其他位置更新，请刷新后重试");
        List<Long> current = selectionIds(user.getId());
        List<Long> reordered = request.collectionIds();
        if (reordered.size() > 1000 || new HashSet<>(reordered).size() != reordered.size() || !new HashSet<>(current).equals(new HashSet<>(reordered)))
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "排序列表必须完整且不能重复");
        normalizeOrder(user.getId(), reordered);
        bumpPreference(user.getId(), currentVersion);
        return selectionView(user.getId());
    }

    @PutMapping("/collections/{collectionId}/posts/{postId}")
    @Transactional
    public CollectionView addPost(@PathVariable long collectionId, @PathVariable long postId, HttpSession session, @RequestParam long revision) {
        UserAccount user = currentUser(session);
        CommunityCollection collection = ownedCollection(collectionId, user.getId());
        long actualRevision = jdbc.queryForObject("select revision from community_collections where id=? for update", Long.class, collectionId);
        if (revision != actualRevision) throw new ApiProblem(HttpStatus.CONFLICT,"专题已更新，保留当前输入后重新加载");
        entityManager.refresh(collection);
        ContentPost post = posts.findById(postId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "内容不存在"));
        if (!Objects.equals(post.getAuthorId(), user.getId())) throw new ApiProblem(HttpStatus.FORBIDDEN, "只能将自己发布的内容加入专题");
        Integer count = jdbc.queryForObject("select count(*) from collection_entries where collection_id=? and post_id=?", Integer.class, collectionId, postId);
        if (count == null || count == 0) {
            Integer totalEntries = jdbc.queryForObject("select count(*) from collection_entries where collection_id=?", Integer.class, collectionId);
            if (totalEntries >= 500) throw new ApiProblem(HttpStatus.BAD_REQUEST,"专题最多包含 500 个条目");
            List<Long> sectionIds = jdbc.query("select id from collection_sections where collection_id=? order by sort_order,id limit 1", (rs, row) -> rs.getLong(1), collectionId);
            long sectionId;
            if (sectionIds.isEmpty()) {
                jdbc.update("insert into collection_sections(collection_id,title,description,sort_order,created_at,updated_at) values(?,'专题内容','',0,current_timestamp,current_timestamp)", collectionId);
                sectionId = jdbc.queryForObject("select id from collection_sections where collection_id=? order by sort_order,id limit 1", Long.class, collectionId);
            } else sectionId = sectionIds.get(0);
            Integer next = jdbc.queryForObject("select coalesce(max(sort_order),-1)+1 from collection_entries where section_id=?", Integer.class, sectionId);
            jdbc.update("insert into collection_entries(collection_id,section_id,kind,sort_order,title,annotation,body,post_id,external_url,created_at,updated_at) values(?,?,'POST',?,null,'',null,?,null,current_timestamp,current_timestamp)", collectionId, sectionId, next == null ? 0 : next, postId);
            jdbc.update("update community_collections set revision=revision+1,updated_at=current_timestamp where id=?",collectionId);
            entityManager.refresh(collection);
        }
        return collectionView(collection);
    }

    @DeleteMapping("/collections/{collectionId}/posts/{postId}")
    @Transactional
    public CollectionView removePost(@PathVariable long collectionId, @PathVariable long postId, HttpSession session, @RequestParam long revision) {
        UserAccount user = currentUser(session);
        CommunityCollection collection = ownedCollection(collectionId, user.getId());
        long actualRevision = jdbc.queryForObject("select revision from community_collections where id=? for update", Long.class, collectionId);
        if (revision != actualRevision) throw new ApiProblem(HttpStatus.CONFLICT,"专题已更新，保留当前输入后重新加载");
        entityManager.refresh(collection);
        if (jdbc.update("delete from collection_entries where collection_id=? and post_id=?", collectionId, postId) > 0) {
            jdbc.update("update community_collections set revision=revision+1,updated_at=current_timestamp where id=?",collectionId);
            entityManager.refresh(collection);
        }
        return collectionView(collection);
    }

    private CommunityCollection ownedCollection(long collectionId, Long userId) {
        CommunityCollection collection = collections.findById(collectionId).orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, "专题不存在"));
        if (!Objects.equals(collection.getOwnerId(), userId)) throw new ApiProblem(HttpStatus.FORBIDDEN, "只能管理自己创建的专题");
        return collection;
    }

    private long lockPreference(Long userId) {
        jdbc.queryForObject("select id from user_accounts where id=? for update", Long.class, userId);
        try { return jdbc.queryForObject("select version from user_collection_preferences where user_id=? for update", Long.class, userId); }
        catch (EmptyResultDataAccessException missing) {
            jdbc.update("insert into user_collection_preferences(user_id,version,updated_at) values(?,0,current_timestamp)", userId);
            return jdbc.queryForObject("select version from user_collection_preferences where user_id=? for update", Long.class, userId);
        }
    }

    private void bumpPreference(Long userId, long version) {
        jdbc.update("update user_collection_preferences set version=?,updated_at=current_timestamp where user_id=? and version=?", version + 1, userId, version);
    }

    private List<Long> selectionIds(Long userId) {
        return jdbc.query("select collection_id from user_collection_selections where user_id=? order by sort_order", (rs, row) -> rs.getLong(1), userId);
    }

    private void normalizeOrder(Long userId, List<Long> ids) {
        jdbc.update("update user_collection_selections set sort_order=sort_order+1001 where user_id=?", userId);
        for (int i = 0; i < ids.size(); i++) jdbc.update("update user_collection_selections set sort_order=? where user_id=? and collection_id=?", i, userId, ids.get(i));
    }

    private SelectionView selectionView(Long userId) {
        long version;
        try { version = jdbc.queryForObject("select version from user_collection_preferences where user_id=?", Long.class, userId); }
        catch (EmptyResultDataAccessException missing) { version = 0; }
        List<SelectedCollection> rows = jdbc.query("select c.id,p.name,p.description,p.owner_name,c.created_at,p.updated_at,s.sort_order,p.item_count as post_count from user_collection_selections s join community_collections c on c.id=s.collection_id join collection_publications p on p.collection_id=c.id where s.user_id=? and c.status='PUBLISHED' order by s.sort_order", (rs, row) ->
                new SelectedCollection(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getString("owner_name"), rs.getInt("post_count"), rs.getInt("sort_order")), userId);
        return new SelectionView(version, rows);
    }

    private CollectionView collectionView(CommunityCollection row) {
        Integer count = jdbc.queryForObject("select count(*) from collection_entries where collection_id=?", Integer.class, row.getId());
        return new CollectionView(row.getId(), row.getName(), row.getDescription(), row.getOwnerName(), count == null ? 0 : count, row.getUpdatedAt(), row.getFormat().name(), row.getStatus().name(), row.getRevision());
    }

    private UserAccount currentUser(HttpSession session) { return AuthController.requireUser(session, users); }
    private ProfileView profileOf(UserAccount user) {
        Integer selectedCount = jdbc.queryForObject("select count(*) from user_collection_selections where user_id=?", Integer.class, user.getId());
        return new ProfileView(user.getId(), user.getEmail(), user.getDisplayName(), user.getBio(), user.getCreatedAt(),
                posts.countByAuthorId(user.getId()), collections.countByOwnerId(user.getId()), selectedCount == null ? 0 : selectedCount);
    }
    private <T> PageView<T> page(List<T> rows, int page, int size) {
        boolean more = rows.size() > size;
        return new PageView<>(more ? rows.subList(0, size) : rows, page, size, more);
    }

    public record ProfileRequest(@NotBlank @Size(max = 40) String displayName, @Size(max = 500) String bio) {}
    public record ProfileView(Long id, String email, String displayName, String bio, java.time.Instant createdAt, long postCount, long collectionCount, int selectedCollectionCount) {}
    public record PageView<T>(List<T> items, int page, int size, boolean hasMore) {}
    public record CollectionView(Long id, String name, String description, String ownerName, int itemCount, java.time.Instant updatedAt, String format, String status, long revision) {}
    public record SelectedCollection(Long id, String name, String description, String ownerName, int itemCount, int sortOrder) {}
    public record SelectionView(long version, List<SelectedCollection> items) {}
    public record ReorderRequest(long version, @Size(max = 1000) List<Long> collectionIds) {}
}
