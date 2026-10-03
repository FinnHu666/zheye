package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

/** Latest explicitly published snapshot. Work-in-progress lives in the existing tables. */
@Entity
@Table(name = "collection_publications")
public class CollectionPublication {
    @Id @Column(name = "collection_id") private Long collectionId;
    @Column(nullable = false, length = 80) private String name;
    @Column(nullable = false, length = 260) private String description;
    @Column(name = "owner_name", nullable = false, length = 40) private String ownerName;
    @Column(name = "item_count", nullable = false) private int itemCount;
    @Column(name = "schema_version", nullable = false) private int schemaVersion;
    @Column(nullable = false, columnDefinition = "text") private String document;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected CollectionPublication() {}
}
