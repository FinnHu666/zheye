package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "collection_posts",
        uniqueConstraints = @UniqueConstraint(name = "uq_collection_post", columnNames = {"collection_id", "post_id"}),
        indexes = @Index(name = "idx_collection_posts_post", columnList = "post_id,collection_id"))
public class CollectionPost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collection_id", nullable = false) private Long collectionId;
    @Column(name = "post_id", nullable = false) private Long postId;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    protected CollectionPost() {}
}
