package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "content_posts", indexes = {
        @Index(name = "idx_post_type_created", columnList = "type,createdAt,id"),
        @Index(name = "idx_post_created", columnList = "createdAt,id")
})
public class ContentPost {
    public enum Type { ARTICLE, TOPIC }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Type type;
    @Column(nullable = false, length = 120) private String title;
    @Column(nullable = false, length = 280) private String summary;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Column(nullable = false) private Long authorId;
    @Column(nullable = false, length = 40) private String authorName;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private int likes;

    protected ContentPost() {}
    public ContentPost(Type type, String title, String summary, String body, Long authorId, String authorName) {
        this.type=type; this.title=title; this.summary=summary; this.body=body; this.authorId=authorId; this.authorName=authorName;
    }
    public Long getId(){return id;} public Type getType(){return type;} public String getTitle(){return title;}
    public String getSummary(){return summary;} public String getBody(){return body;} public Long getAuthorId(){return authorId;}
    public String getAuthorName(){return authorName;} public Instant getCreatedAt(){return createdAt;} public int getLikes(){return likes;}
    public void like(){likes++;}
}
