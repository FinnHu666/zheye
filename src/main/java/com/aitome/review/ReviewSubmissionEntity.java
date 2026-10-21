package com.aitome.review;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "review_submissions", indexes = {
        @Index(name = "ix_review_sub_status", columnList = "status,created_at,id"),
        @Index(name = "ix_review_sub_author", columnList = "author_id,created_at,id")})
public class ReviewSubmissionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "state_id", nullable = false) private Long stateId;
    @Column(name = "author_id", nullable = false) private Long authorId;
    @Column(name = "content_type", nullable = false, length = 12) private String contentType;
    @Column(nullable = false, length = 120, columnDefinition = "varchar(120) default '' not null") private String title = "";
    @Column(nullable = false) private long generation;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text") private String snapshotJson;
    @Column(name = "snapshot_version", nullable = false) private long snapshotVersion;
    @Column(nullable = false, length = 30) private String triggers;
    @Column(name = "matched_phrases", nullable = false, length = 400, columnDefinition = "varchar(400) default '' not null") private String matchedPhrases = "";
    @Column(nullable = false) private int attempts;
    @Column(nullable = false, length = 8, columnDefinition = "varchar(8) default 'REVIEW' not null") private String kind = "REVIEW";
    @Column(nullable = false, length = 8, columnDefinition = "varchar(8) default 'MEDIUM' not null") private String level = "MEDIUM";
    @Column(name = "flow_id") private Long flowId;
    @Column(nullable = false, length = 10) private String status;
    @Column(name = "result_note", nullable = false, length = 200, columnDefinition = "varchar(200) default '' not null") private String resultNote = "";
    @Column(name = "operator_id") private Long operatorId;
    @Column(name = "operator_name", length = 40) private String operatorName;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "closed_at") private Instant closedAt;
    protected ReviewSubmissionEntity() {}
}
