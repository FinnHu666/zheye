package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 作者侧的文章草稿（FINNS-18）。content_posts 仍只存公开版；发布/更新时把草稿冻结为提交快照，通过后更新同一公开行，
 * 并把 postId 关联到草稿。草稿全空不创建、三项非空约束由服务保证（PostgreSQL 另有 CHECK，见 V18）。
 */
@Entity
@Table(name = "article_drafts", indexes = {
        @Index(name = "ix_article_drafts_author", columnList = "author_id,updated_at,id")
})
public class ArticleDraft {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "author_id", nullable = false) private Long authorId;
    @Column(name = "post_id", unique = true) private Long postId;
    @Column(nullable = false, length = 240, columnDefinition = "varchar(240) default '' not null") private String title = ""; /* FINNS-18 D3：H2 的 varchar(n) 按 UTF-16 单元计，为让“按码点计”的上限在 H2 开发/测试库同样成立而放宽；PostgreSQL 由迁移 SQL 建表，varchar(n) 按字符计（未实测，B7）。 */
    @Column(nullable = false, length = 560, columnDefinition = "varchar(560) default '' not null") private String summary = "";
    @Column(nullable = false, columnDefinition = "text default '' not null") private String body = "";
    @Column(nullable = false) private long version = 1;
    @Column(name = "last_request_id", length = 64) private String lastRequestId;
    @Column(name = "last_request_version") private Long lastRequestVersion;
    @Column(name = "last_request_hash", length = 64) private String lastRequestHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected ArticleDraft() {}
    public ArticleDraft(Long authorId, String title, String summary, String body) {
        this.authorId = authorId; this.title = title == null ? "" : title; this.summary = summary == null ? "" : summary; this.body = body == null ? "" : body;
    }

    public Long getId() { return id; }
    public Long getAuthorId() { return authorId; }
    public Long getPostId() { return postId; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public String getBody() { return body; }
    public long getVersion() { return version; }
    public String getLastRequestId() { return lastRequestId; }
    public Long getLastRequestVersion() { return lastRequestVersion; }
    public String getLastRequestHash() { return lastRequestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** 手动保存：内容有变化才加版本、改更新时间；返回是否有变化。 */
    public boolean applyContent(String newTitle, String newSummary, String newBody) {
        String t = newTitle == null ? "" : newTitle, s = newSummary == null ? "" : newSummary, b = newBody == null ? "" : newBody;
        if (t.equals(title) && s.equals(summary) && b.equals(body)) return false;
        title = t; summary = s; body = b; version++; updatedAt = Instant.now();
        return true;
    }
    public void linkPost(Long postId) { this.postId = postId; }
    public void recordRequest(String requestId, String hash) { this.lastRequestId = requestId; this.lastRequestVersion = version; this.lastRequestHash = hash; }
}
