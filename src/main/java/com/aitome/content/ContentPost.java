package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "content_posts", indexes = {
        @Index(name = "idx_post_type_created", columnList = "type,createdAt,id"),
        @Index(name = "idx_post_created", columnList = "createdAt,id")
})
public class ContentPost {
    // FINNS-18：普通话题已整体移除（历史话题由 V19 删除），内容类型只剩文章。
    public enum Type { ARTICLE }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Type type;
    @Column(nullable = false, length = 240) private String title; /* FINNS-18 D3：H2 的 varchar(n) 按 UTF-16 单元计，为让“按码点计”的上限在 H2 开发/测试库同样成立而放宽；PostgreSQL 由迁移 SQL 建表，varchar(n) 按字符计（未实测，B7）。 */
    @Column(nullable = false, length = 560) private String summary;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Column(nullable = false) private Long authorId;
    @Column(nullable = false, length = 40) private String authorName;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private int likes;

    @Column(name = "moderation_state", nullable = false, length = 10, columnDefinition = "varchar(10) default 'VISIBLE' not null") private String moderationState = "VISIBLE";
    @Column(name = "review_submission_id") private Long reviewSubmissionId;
    /** 作者更新已发布文章并通过后写入；空表示从未被更新（FINNS-18，详情页显示“更新于”）。 */
    @Column(name = "updated_at") private Instant updatedAt;
    protected ContentPost() {}
    public ContentPost(Type type, String title, String summary, String body, Long authorId, String authorName) {
        this.type=type; this.title=title; this.summary=summary; this.body=body; this.authorId=authorId; this.authorName=authorName;
    }
    public Long getId(){return id;} public Type getType(){return type;} public String getTitle(){return title;}
    public String getSummary(){return summary;} public String getBody(){return body;} public Long getAuthorId(){return authorId;}
    public String getAuthorName(){return authorName;} public Instant getCreatedAt(){return createdAt;} public int getLikes(){return likes;}
    public void like(){likes++;}
    public Instant getUpdatedAt(){return updatedAt;}
    @com.fasterxml.jackson.annotation.JsonIgnore public String getModerationState(){return moderationState;}
}
