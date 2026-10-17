package com.aitome.stats;

import com.aitome.access.PublicEndpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PublicEndpoint("首页公开统计；受限专题不计入")
public class SiteStatsController {
    private static final String STATS_SQL = """
            select
              (select count(*) from user_accounts) as creator_count,
              (select count(*) from content_posts) as content_count,
              (select count(*) from community_collections c join collection_publications p on p.collection_id=c.id where c.status='PUBLISHED' and p.visibility='PUBLIC') as collection_count
            """;

    private final JdbcTemplate jdbc;
    private final SiteVisitService visits;

    public SiteStatsController(JdbcTemplate jdbc, SiteVisitService visits) {
        this.jdbc = jdbc;
        this.visits = visits;
    }

    @GetMapping("/api/stats")
    public SiteStats stats() {
        return jdbc.queryForObject(STATS_SQL, (rs, row) -> new SiteStats(
                rs.getLong("creator_count"),
                rs.getLong("content_count"),
                rs.getLong("collection_count"),
                visits.visits().orElse(null)));
    }

    /** visits 为 null 表示访问量暂不可用（首页显示“—”），其余数字照常返回。 */
    public record SiteStats(long creatorCount, long contentCount, long collectionCount, SiteVisitService.Visits visits) {}
}
