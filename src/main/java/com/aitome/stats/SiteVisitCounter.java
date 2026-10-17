package com.aitome.stats;

import jakarta.persistence.*;
import java.time.Instant;

/** 全站累计访问次数（单行）。PostgreSQL 由 Flyway V11 建表；H2 开发库由此实体建表。 */
@Entity
@Table(name = "site_visit_counter")
public class SiteVisitCounter {
    @Id private Short id;
    @Column(nullable = false) private long total;
    @Column(name = "counted_since", nullable = false) private Instant countedSince;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected SiteVisitCounter() {}
}
