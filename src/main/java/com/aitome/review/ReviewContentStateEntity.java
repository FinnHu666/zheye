package com.aitome.review;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "review_content_state", uniqueConstraints = @UniqueConstraint(name = "ux_review_state_key", columnNames = {"author_id", "content_type", "content_key"}))
public class ReviewContentStateEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "author_id", nullable = false) private Long authorId;
    @Column(name = "content_type", nullable = false, length = 12) private String contentType;
    @Column(name = "content_key", nullable = false, length = 80) private String contentKey;
    @Column(name = "target_id") private Long targetId;
    @Column(nullable = false, columnDefinition = "int default 0 not null") private int attempts;
    @Column(nullable = false, columnDefinition = "bigint default 1 not null") private long generation = 1;
    @Column(name = "approved_once", nullable = false, columnDefinition = "boolean default false not null") private boolean approvedOnce;
    @Column(nullable = false, columnDefinition = "boolean default false not null") private boolean legacy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected ReviewContentStateEntity() {}
}
