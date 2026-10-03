package com.aitome.content;

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

    public PublicOutline capture(long id) {
        PublicCollection collection = jdbc.queryForObject("select * from community_collections where id=?", (rs, n) ->
            new PublicCollection(id, rs.getString("name"), rs.getString("description"), rs.getString("owner_name"),
                CommunityCollection.Format.valueOf(rs.getString("format")), rs.getString("audience"), rs.getString("goal"),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant()), id);
        List<PublicSection> sections = jdbc.query("select * from collection_sections where collection_id=? order by sort_order,id", (rs, n) -> {
            long sectionId = rs.getLong("id");
            List<PublicEntry> entries = jdbc.query("select e.*,p.title as post_title,p.summary as post_summary,p.author_name from collection_entries e left join content_posts p on p.id=e.post_id where e.collection_id=? and e.section_id=? order by e.sort_order,e.id",
                (entry, i) -> new PublicEntry(entry.getLong("id"), CollectionEntry.Kind.valueOf(entry.getString("kind")), entry.getInt("sort_order"),
                    entry.getString("title"), entry.getString("annotation"), entry.getString("body"), (Long)entry.getObject("post_id"),
                    entry.getString("post_title"), entry.getString("post_summary"), entry.getString("author_name"), entry.getString("external_url")), id, sectionId);
            return new PublicSection(sectionId, rs.getString("title"), rs.getString("description"), rs.getInt("sort_order"), entries);
        }, id);
        return new PublicOutline(collection, sections);
    }

    /** Caller owns the transaction and has locked the collection. */
    public void publishSnapshot(long id) {
        PublicOutline outline = capture(id);
        try {
            jdbc.update("delete from collection_publications where collection_id=?", id);
            jdbc.update("insert into collection_publications(collection_id,name,description,owner_name,item_count,schema_version,document,updated_at) values(?,?,?,?,?,1,?,?)",
                id, outline.collection().name(), outline.collection().description(), outline.collection().ownerName(),
                outline.sections().stream().mapToInt(s -> s.entries().size()).sum(), json.writeValueAsString(outline), java.sql.Timestamp.from(outline.collection().updatedAt()));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize publication", e); }
    }

    public PublicOutline read(long id) {
        List<String> documents = jdbc.query("select p.document from collection_publications p join community_collections c on c.id=p.collection_id where c.id=? and c.status='PUBLISHED'", (rs, n) -> rs.getString(1), id);
        if (documents.isEmpty()) throw new ApiProblem(HttpStatus.NOT_FOUND, "专题不存在");
        try {
            PublicOutline outline = json.readValue(documents.get(0), PublicOutline.class);
            // Frozen cards never expose data for a reference that has since become unavailable.
            Set<Long> refs = new HashSet<>();
            outline.sections().forEach(s -> s.entries().forEach(e -> { if (e.postId() != null) refs.add(e.postId()); }));
            Set<Long> available = new HashSet<>();
            if (!refs.isEmpty()) {
                String placeholders = String.join(",", Collections.nCopies(refs.size(), "?"));
                available.addAll(jdbc.query("select id from content_posts where id in (" + placeholders + ")", (rs,n) -> rs.getLong(1), refs.toArray()));
            }
            return new PublicOutline(outline.collection(), outline.sections().stream().map(s -> new PublicSection(s.id(), s.title(), s.description(), s.sortOrder(),
                s.entries().stream().map(e -> e.kind() == CollectionEntry.Kind.POST && !available.contains(e.postId())
                    ? new PublicEntry(e.id(), e.kind(), e.sortOrder(), e.title(), e.annotation(), null, null, "原文已不可用", "", "", null) : e).toList())).toList());
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read publication", e); }
    }
}
