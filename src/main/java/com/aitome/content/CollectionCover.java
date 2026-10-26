package com.aitome.content;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="collection_covers")
public class CollectionCover {
    @Id @Column(length=36) private String id;
    @Column(name="collection_id",nullable=false) private Long collectionId;
    @Column(name="image_bytes",nullable=false,columnDefinition="bytea") private byte[] imageBytes;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(nullable=false,columnDefinition="boolean default false not null") private boolean removed;
    protected CollectionCover() {}
}
