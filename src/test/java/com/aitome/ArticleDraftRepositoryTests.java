package com.aitome;

import com.aitome.content.ArticleDraft;
import com.aitome.content.ArticleDraftRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B1：草稿实体能建表与读写；postId 唯一；applyContent 只在内容变化时加版本。作者与公开文章用真实行（PostgreSQL 有外键，H2 没有）。 */
@SpringBootTest @AutoConfigureMockMvc
class ArticleDraftRepositoryTests {
    @Autowired ArticleDraftRepository drafts; @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    final java.util.ArrayList<Long> posts = new java.util.ArrayList<>();
    long user() throws Exception { return new AccessTestSupport(mvc, json, jdbc).register("b1repo").id(); }
    long post(long author, String title) {
        jdbc.update("insert into content_posts(type,title,summary,body,author_id,author_name,created_at,likes,moderation_state) values('ARTICLE',?,'s','b',?,'x',current_timestamp,0,'VISIBLE')", title, author);
        long id = jdbc.queryForObject("select id from content_posts where title=? and author_id=?", Long.class, title, author); posts.add(id); return id;
    }
    final java.util.ArrayList<Long> created = new java.util.ArrayList<>();

    @AfterEach void cleanup() { created.forEach(id -> jdbc.update("delete from article_drafts where id=?", id)); created.clear(); posts.forEach(id -> jdbc.update("delete from content_posts where id=?", id)); posts.clear(); }

    ArticleDraft save(ArticleDraft d) { ArticleDraft saved = drafts.saveAndFlush(d); created.add(saved.getId()); return saved; }

    @Test void persistsAndReadsBackWithDefaults() throws Exception {
        ArticleDraft saved = save(new ArticleDraft(user(), "标题", null, null));
        ArticleDraft read = drafts.findById(saved.getId()).orElseThrow();
        assertEquals("标题", read.getTitle()); assertEquals("", read.getSummary()); assertEquals("", read.getBody());
        assertEquals(1, read.getVersion()); assertNull(read.getPostId()); assertNotNull(read.getCreatedAt());
    }

    @Test void applyContentBumpsVersionOnlyWhenChanged() throws Exception {
        ArticleDraft d = save(new ArticleDraft(user(), "a", "b", "c"));
        assertFalse(d.applyContent("a", "b", "c")); assertEquals(1, d.getVersion());
        assertTrue(d.applyContent("a2", "b", "c")); assertEquals(2, d.getVersion());
        assertTrue(d.applyContent("a2", null, "c")); assertEquals(3, d.getVersion(), "null 与空串等价于清空");
        assertEquals("", d.getSummary());
    }

    @Test void postIdIsUniqueWhenLinked() throws Exception {
        long author = user(), post = post(author, "b1-unique-post");
        ArticleDraft first = save(new ArticleDraft(author, "x", "y", "z"));
        first.linkPost(post); save(first);
        ArticleDraft second = new ArticleDraft(author, "x2", "y2", "z2"); second.linkPost(post);
        assertThrows(DataIntegrityViolationException.class, () -> drafts.saveAndFlush(second));
        assertTrue(drafts.findByPostId(post).isPresent());
    }

    @Test void recordsLastRequestForIdempotency() throws Exception {
        ArticleDraft d = save(new ArticleDraft(user(), "t", "s", "b"));
        d.recordRequest("req-1", "h".repeat(64)); drafts.saveAndFlush(d);
        ArticleDraft read = drafts.findById(d.getId()).orElseThrow();
        assertEquals("req-1", read.getLastRequestId()); assertEquals(1L, read.getLastRequestVersion()); assertEquals(64, read.getLastRequestHash().length());
    }

    @Test void contentPostGetsUpdatedAtColumnAndStaysNullByDefault() throws Exception {
        jdbc.update("insert into content_posts(type,title,summary,body,author_id,author_name,created_at,likes,moderation_state) values('ARTICLE','b1-updatedat','s','b',?,'x',current_timestamp,0,'VISIBLE')", user());
        List<Object> updated = jdbc.query("select updated_at from content_posts where title='b1-updatedat'", (rs, n) -> rs.getObject(1));
        assertEquals(1, updated.size()); assertNull(updated.get(0));
        jdbc.update("delete from content_posts where title='b1-updatedat'");
    }
}
