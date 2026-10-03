package com.aitome.user;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_accounts", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120) private String email;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false, length = 40) private String displayName;
    @Column(nullable = false, length = 500) private String bio = "";
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();

    protected UserAccount() {}
    public UserAccount(String email, String passwordHash, String displayName) {
        this.email = email.toLowerCase(); this.passwordHash = passwordHash; this.displayName = displayName;
    }
    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public Instant getCreatedAt() { return createdAt; }
    public void updateProfile(String displayName, String bio) { this.displayName = displayName; this.bio = bio; }
}
