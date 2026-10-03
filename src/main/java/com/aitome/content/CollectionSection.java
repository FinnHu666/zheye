package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "collection_sections",
        uniqueConstraints = @UniqueConstraint(name = "uq_collection_section_order", columnNames = {"collection_id", "sort_order"}),
        indexes = @Index(name = "idx_collection_section_collection", columnList = "collection_id,sort_order,id"))
public class CollectionSection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collection_id", nullable = false) private Long collectionId;
    @Column(nullable = false, length = 80) private String title;
    @Column(nullable = false, length = 500) private String description = "";
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected CollectionSection() {}
}
