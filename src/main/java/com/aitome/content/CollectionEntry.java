package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "collection_entries",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_collection_entry_order", columnNames = {"section_id", "sort_order"}),
                @UniqueConstraint(name = "uq_collection_entry_post", columnNames = {"collection_id", "post_id"})
        },
        indexes = {
                @Index(name = "idx_collection_entry_collection", columnList = "collection_id,section_id,sort_order,id"),
                @Index(name = "idx_collection_entry_post", columnList = "post_id,collection_id")
        })
public class CollectionEntry {
    public enum Kind { NOTE, POST, LINK }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collection_id", nullable = false) private Long collectionId;
    @Column(name = "section_id", nullable = false) private Long sectionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Kind kind;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(length = 160) private String title;
    @Column(nullable = false, length = 1000) private String annotation = "";
    @Column(columnDefinition = "text") private String body;
    @Column(name = "post_id") private Long postId;
    @Column(name = "external_url", length = 2000) private String externalUrl;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected CollectionEntry() {}
}
