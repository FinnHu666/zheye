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
    @Column(nullable = false, length = 240, columnDefinition = "varchar(240) default '' not null") private String title = ""; /* FINNS-18 D3：H2 的 varchar(n) 按 UTF-16 单元计，为让“按码点计”的上限在 H2 开发/测试库同样成立而放宽；PostgreSQL 由迁移 SQL 建表，varchar(n) 按字符计（未实测，B7）。 */
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
