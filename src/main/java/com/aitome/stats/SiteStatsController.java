package com.aitome.stats;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SiteStatsController {
    private static final String STATS_SQL = """
            select
              (select count(*) from user_accounts) as creator_count,
              (select count(*) from content_posts) as content_count,
              (select count(*) from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED') as collection_count
            """;

    private final JdbcTemplate jdbc;

    public SiteStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/stats")
    public SiteStats stats() {
        return jdbc.queryForObject(STATS_SQL, (rs, row) -> new SiteStats(
                rs.getLong("creator_count"),
                rs.getLong("content_count"),
                rs.getLong("collection_count")));
    }

    public record SiteStats(long creatorCount, long contentCount, long collectionCount) {}
}
