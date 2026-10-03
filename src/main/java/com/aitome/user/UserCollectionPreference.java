package com.aitome.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_collection_preferences")
public class UserCollectionPreference {
    @Id @Column(name = "user_id") private Long userId;
    @Column(nullable = false) private long version;
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected UserCollectionPreference() {}
}
