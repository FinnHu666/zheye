package com.aitome.content;

import com.aitome.access.AccessContext;
import com.aitome.access.Permission;
import com.aitome.common.ApiExceptionHandler.ApiProblem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.aitome.content.CollectionWorkspaceController.*;

@Service
public class CollectionPublicationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CollectionPublicationService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public String coverId(String document) { try { PublicOutline outline=json.readValue(document,PublicOutline.class);return outline.metadata()==null?null:outline.metadata().coverId(); } catch(JsonProcessingException e){throw new IllegalStateException(e);} }
    @org.springframework.beans.factory.annotation.Value("${finns.collections.scheduling-enabled:false}") private boolean schedulingEnabled;

    public void validateSchedule(java.time.Instant due) {
        if (due == null) return;
        if (!schedulingEnabled) throw new ApiProblem(HttpStatus.BAD_REQUEST,"定时发布尚未开启");
        java.time.Instant now = java.time.Instant.now();
        if (due.isBefore(now.plusSeconds(300)) || due.isAfter(now.plusSeconds(366L*86400))) throw new ApiProblem(HttpStatus.BAD_REQUEST,"定时时间须在5分钟后至1年内");
    }
    public void clearSchedule(long id) {
        jdbc.update("update community_collections set scheduled_at=null,scheduled_document=null,scheduled_visibility=null,scheduled_ready=null where id=?",id);
    }
    public void cancelSchedule(long id) {
        List<Boolean> ready=jdbc.queryForList("select scheduled_ready from community_collections where id=?",Boolean.class,id);
        if(!ready.isEmpty()&&Boolean.TRUE.equals(ready.get(0))) {
            List<Long> flows=jdbc.queryForList("select f.id from review_flows f join review_content_state s on s.id=f.state_id where s.content_type='COLLECTION' and s.target_id=? order by f.id desc limit 1",Long.class,id);
            if(!flows.isEmpty()) {
                jdbc.update("update review_flows set status='INVALID' where id=?",flows.get(0));
                jdbc.update("insert into review_flow_events(flow_id,stage,title,note,detail,occurred_at) values(?,'RESULT','取消定时公开','私人稿与旧发布版保留','',current_timestamp)",flows.get(0));
            }
        }
        clearSchedule(id);
    }
    public void schedule(long id, PublicOutline outline, String visibility, boolean ready) {
        try { jdbc.update("update community_collections set scheduled_at=?,scheduled_document=?,scheduled_visibility=?,scheduled_ready=? where id=?", java.sql.Timestamp.from(outline.scheduledAt()),json.writeValueAsString(outline),visibility,ready,id); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        if(ready) jdbc.update("insert into collection_activity(collection_id,action,revision,occurred_at) select id,'SCHEDULE',revision,current_timestamp from community_collections where id=?",id);
    }
    /** Approval must publish the submitted version, even when the working draft has changed. */
    public boolean activate(long id, PublicOutline outline, String visibility) {
        assertReferences(outline);
        if (outline.scheduledAt()!=null && outline.scheduledAt().isAfter(java.time.Instant.now())) { schedule(id,outline,visibility,true); return false; }
        clearSchedule(id); publishFrozen(id,outline,visibility); return true;
    }
    public void assertReferences(PublicOutline outline) {
        for (PublicSection section : outline.sections()) for (PublicEntry entry : section.entries())
            if (entry.kind()==CollectionEntry.Kind.POST && (entry.postId()==null || jdbc.queryForList("select id from content_posts where id=? and moderation_state='VISIBLE' for update",Long.class,entry.postId()).isEmpty()))
                throw new ApiProblem(HttpStatus.CONFLICT,"引用文章已不可用，请修正后重新提交");
    }

    public PublicOutline capture(long id) {
        PublicCollection collection = jdbc.queryForObject("select * from community_collections where id=?", (rs, n) ->
            new PublicCollection(id, rs.getString("name"), rs.getString("description"), rs.getString("owner_name"),
                CommunityCollection.Format.valueOf(rs.getString("format")), rs.getString("audience"), rs.getString("goal"),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant()), id);
        List<PublicSection> sections = jdbc.query("select * from collection_sections where collection_id=? order by sort_order,id", (rs, n) -> {
            long sectionId = rs.getLong("id");
            List<PublicEntry> entries = jdbc.query("select e.*,p.title as post_title,p.summary as post_summary,p.author_name from collection_entries e left join content_posts p on p.id=e.post_id and p.moderation_state='VISIBLE' where e.collection_id=? and e.section_id=? order by e.sort_order,e.id",
                (entry, i) -> new PublicEntry(entry.getLong("id"), CollectionEntry.Kind.valueOf(entry.getString("kind")), entry.getInt("sort_order"),
                    entry.getString("title"), entry.getString("annotation"), entry.getString("body"), (Long)entry.getObject("post_id"),
                    entry.getString("post_title"), entry.getString("post_summary"), entry.getString("author_name"), entry.getString("external_url")), id, sectionId);
            return new PublicSection(sectionId, rs.getString("title"), rs.getString("description"), rs.getInt("sort_order"), entries);
        }, id);
        return new PublicOutline(collection, sections, CollectionMetadata.parse(jdbc.queryForObject("select wizard_metadata from community_collections where id=?",String.class,id)),null);
    }

    /** Caller owns the transaction and has locked the collection. */
    public void publishSnapshot(long id) {
        String visibility = jdbc.queryForObject("select visibility from community_collections where id=?", String.class, id);
        publishFrozen(id, capture(id), visibility);
    }

    /** 发布已冻结的快照（FINNS-17 审批通过时使用）：不读取当前工作稿。 */
    public void publishFrozen(long id, PublicOutline outline, String visibility) {
        try {
            jdbc.update("delete from collection_publications where collection_id=?", id);
            jdbc.update("insert into collection_publications(collection_id,name,description,owner_name,item_count,schema_version,document,updated_at,visibility) values(?,?,?,?,?,1,?,?,?)",
                id, outline.collection().name(), outline.collection().description(), outline.collection().ownerName(),
                outline.sections().stream().mapToInt(s -> s.entries().size()).sum(), json.writeValueAsString(outline), java.sql.Timestamp.from(outline.collection().updatedAt()), visibility);
            jdbc.update("update collection_publications set category=? where collection_id=?",outline.metadata()==null?"":outline.metadata().category(),id);
            jdbc.update("insert into collection_activity(collection_id,action,revision,occurred_at) select id,'PUBLISH',revision,current_timestamp from community_collections where id=?",id);
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize publication", e); }
    }

    /** 读取公开版本；受限专题对无权限者拒绝（未登录 401，已登录 403）。 */
    public PublicOutline read(long id, AccessContext viewer) {
        List<String[]> rows = jdbc.query("select p.document,p.visibility from collection_publications p join community_collections c on c.id=p.collection_id where c.id=? and c.status='PUBLISHED' and c.moderation_state='VISIBLE'",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, id);
        if (rows.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "专题不存在");
        if ("RESTRICTED".equals(rows.get(0)[1]) && !viewer.has(Permission.CONTENT_RESTRICTED)) {
            if (viewer.isGuest()) throw new ApiProblem(HttpStatus.UNAUTHORIZED, "这是受限专题，请先登录后再访问");
            throw new ApiProblem(HttpStatus.FORBIDDEN, "当前角色无权阅读这个专题");
        }
        List<String> documents = List.of(rows.get(0)[0]);
        try {
            PublicOutline outline = json.readValue(documents.get(0), PublicOutline.class);
            // Frozen cards never expose data for a reference that has since become unavailable.
            Set<Long> refs = new HashSet<>();
            outline.sections().forEach(s -> s.entries().forEach(e -> { if (e.postId() != null) refs.add(e.postId()); }));
            Set<Long> available = new HashSet<>();
            if (!refs.isEmpty()) {
                String placeholders = String.join(",", Collections.nCopies(refs.size(), "?"));
                available.addAll(jdbc.query("select id from content_posts where moderation_state='VISIBLE' and id in (" + placeholders + ")", (rs,n) -> rs.getLong(1), refs.toArray()));
            }
            return new PublicOutline(outline.collection(), outline.sections().stream().map(s -> new PublicSection(s.id(), s.title(), s.description(), s.sortOrder(),
                s.entries().stream().map(e -> e.kind() == CollectionEntry.Kind.POST && !available.contains(e.postId())
                    ? new PublicEntry(e.id(), e.kind(), e.sortOrder(), "", "", null, null, "原文已不可用", "", "", null) : e).toList())).toList(),outline.metadata(),null);
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read publication", e); }
    }
}
