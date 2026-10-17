package com.aitome.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="user_ai_credentials")
public class UserAiCredential {
    @Id @Column(name="user_id") private Long userId;
    @Column(nullable=false,length=1024) private String ciphertext;
    @Column(nullable=false,length=32) private String nonce;
    @Column(name="last_four",nullable=false,length=4) private String lastFour;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected UserAiCredential() {}
}
