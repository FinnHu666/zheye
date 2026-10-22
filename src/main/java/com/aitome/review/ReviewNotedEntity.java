package com.aitome.review;

import jakarta.persistence.*;
import java.time.Instant;

/** 低敏感放行留痕。 */
@Entity
@Table(name = "review_noted", indexes = @Index(name = "ix_review_noted_time", columnList = "created_at,id"))
public class ReviewNotedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "content_type", nullable = false, length = 12) private String contentType;
    @Column(name = "content_id", nullable = false) private Long contentId;
    @Column(name = "author_id", nullable = false) private Long authorId;
    @Column(nullable = false, length = 240, columnDefinition = "varchar(240) default '' not null") private String title = ""; /* FINNS-18 D3：H2 的 varchar(n) 按 UTF-16 单元计，为让“按码点计”的上限在 H2 开发/测试库同样成立而放宽；PostgreSQL 由迁移 SQL 建表，varchar(n) 按字符计（未实测，B7）。 */
    @Column(nullable = false, length = 400) private String words;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "reviewed_by") private Long reviewedBy;
    @Column(name = "reviewed_by_name", length = 40) private String reviewedByName;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    protected ReviewNotedEntity() {}
}
