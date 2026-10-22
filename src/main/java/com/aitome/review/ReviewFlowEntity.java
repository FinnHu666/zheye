package com.aitome.review;

import jakarta.persistence.*;
import java.time.Instant;

/** 一次提交的流转（表映射；由 ReviewService 通过 JDBC 读写）。 */
@Entity
@Table(name = "review_flows", indexes = {
        @Index(name = "ix_review_flows_state", columnList = "state_id,started_at,id"),
        @Index(name = "ix_review_flows_author", columnList = "author_id,started_at,id")})
public class ReviewFlowEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "state_id", nullable = false) private Long stateId;
    @Column(name = "author_id", nullable = false) private Long authorId;
    @Column(name = "content_type", nullable = false, length = 12) private String contentType;
    @Column(nullable = false, length = 240, columnDefinition = "varchar(240) default '' not null") private String title = ""; /* FINNS-18 D3：H2 的 varchar(n) 按 UTF-16 单元计，为让“按码点计”的上限在 H2 开发/测试库同样成立而放宽；PostgreSQL 由迁移 SQL 建表，varchar(n) 按字符计（未实测，B7）。 */
    @Column(name = "snapshot_version", nullable = false) private long snapshotVersion;
    @Column(nullable = false, length = 8) private String level;
    @Column(nullable = false, length = 14) private String status;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    protected ReviewFlowEntity() {}
}
