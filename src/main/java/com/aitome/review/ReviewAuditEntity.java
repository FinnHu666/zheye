package com.aitome.review;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "review_audit_log", indexes = @Index(name = "ix_review_audit_time", columnList = "created_at,id"))
public class ReviewAuditEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 14) private String type;
    @Column(nullable = false, length = 10, columnDefinition = "varchar(10) default 'SUCCESS' not null") private String outcome = "SUCCESS";
    @Column(name = "operator_id") private Long operatorId;
    @Column(name = "operator_name", length = 40) private String operatorName;
    @Column(name = "author_id") private Long authorId;
    @Column(name = "content_type", length = 12) private String contentType;
    @Column(name = "content_id") private Long contentId;
    @Column(name = "submission_id") private Long submissionId;
    @Column(name = "snapshot_version") private Long snapshotVersion;
    @Column(length = 30) private String triggers;
    @Column(length = 40) private String phrase;
    @Column(name = "rule_enabled") private Boolean ruleEnabled;
    @Column(length = 200) private String note;
    @Column(name = "result_message", length = 200) private String resultMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected ReviewAuditEntity() {}
}
