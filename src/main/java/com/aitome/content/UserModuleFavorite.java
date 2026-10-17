package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="user_module_favorites")
public class UserModuleFavorite {
    @Id @Column(length=36) private String id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(nullable=false,columnDefinition="text") private String block;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected UserModuleFavorite() {}
}
