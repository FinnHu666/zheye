package com.aitome.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_collection_selections",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_user_collection_selection", columnNames = {"user_id", "collection_id"}),
                @UniqueConstraint(name = "uq_user_collection_order", columnNames = {"user_id", "sort_order"})
        })
public class UserCollectionSelection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "collection_id", nullable = false) private Long collectionId;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    protected UserCollectionSelection() {}
}
