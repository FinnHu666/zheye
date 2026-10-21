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
    @Column(nullable = false, length = 120, columnDefinition = "varchar(120) default '' not null") private String title = "";
    @Column(nullable = false, length = 400) private String words;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "reviewed_by") private Long reviewedBy;
    @Column(name = "reviewed_by_name", length = 40) private String reviewedByName;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    protected ReviewNotedEntity() {}
}
