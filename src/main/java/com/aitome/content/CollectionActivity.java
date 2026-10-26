package com.aitome.content;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="collection_activity")
public class CollectionActivity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="collection_id",nullable=false) private Long collectionId;
    @Column(nullable=false,length=24) private String action;
    @Column(nullable=false) private long revision;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    protected CollectionActivity() {}
}
