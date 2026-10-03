package com.aitome.content;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="comments", indexes=@Index(name="idx_comment_post_created", columnList="postId,createdAt,id"))
public class Comment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long postId;
    @Column(nullable=false) private Long authorId;
    @Column(nullable=false, length=40) private String authorName;
    @Column(nullable=false, length=1000) private String body;
    @Column(nullable=false) private Instant createdAt=Instant.now();
    protected Comment() {}
    public Comment(Long postId, Long authorId, String authorName, String body){this.postId=postId;this.authorId=authorId;this.authorName=authorName;this.body=body;}
    public Long getId(){return id;} public Long getPostId(){return postId;} public Long getAuthorId(){return authorId;}
    public String getAuthorName(){return authorName;} public String getBody(){return body;} public Instant getCreatedAt(){return createdAt;}
}
