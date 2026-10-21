package com.aitome.review;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "review_flow_events", indexes = @Index(name = "ix_review_flow_events_flow", columnList = "flow_id,occurred_at,id"))
public class ReviewFlowEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "flow_id", nullable = false) private Long flowId;
    @Column(nullable = false, length = 10) private String stage;
    @Column(nullable = false, length = 60) private String title;
    @Column(nullable = false, length = 200, columnDefinition = "varchar(200) default '' not null") private String note = "";
    @Column(nullable = false, length = 400, columnDefinition = "varchar(400) default '' not null") private String detail = "";
    @Column(name = "actor_id") private Long actorId;
    @Column(name = "actor_name", length = 40) private String actorName;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    protected ReviewFlowEventEntity() {}
}
