package com.aitome.stats;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class SiteVisitService {
    private final JdbcTemplate jdbc;

    public SiteVisitService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** H2 开发/测试库没有 Flyway 种子行时补齐；PostgreSQL 已由 V11 插入，这里不做任何修改。 */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureCounterRow() {
        Integer rows = jdbc.queryForObject("select count(*) from site_visit_counter where id=1", Integer.class);
        if (rows == null || rows == 0)
            jdbc.update("insert into site_visit_counter(id,total,counted_since,updated_at) values(1,0,current_timestamp,current_timestamp)");
    }

    /** 行锁保证并发进入不丢计。 */
    @Transactional
    public long recordEntry() {
        int updated = jdbc.update("update site_visit_counter set total=total+1,updated_at=current_timestamp where id=1");
        if (updated != 1) {
            // H2 新库在启动初始化完成前收到首个请求时补齐计数行；并发补齐时主键冲突说明另一请求已插入
            try { jdbc.update("insert into site_visit_counter(id,total,counted_since,updated_at) values(1,0,current_timestamp,current_timestamp)"); }
            catch (org.springframework.dao.DuplicateKeyException alreadyInserted) { /* 继续计数 */ }
            updated = jdbc.update("update site_visit_counter set total=total+1,updated_at=current_timestamp where id=1");
            if (updated != 1) throw new IllegalStateException("site_visit_counter row is missing");
        }
        Long total = jdbc.queryForObject("select total from site_visit_counter where id=1", Long.class);
        return total == null ? 0 : total;
    }

    public long currentTotal() {
        Long total = jdbc.queryForObject("select total from site_visit_counter where id=1", Long.class);
        return total == null ? 0 : total;
    }

    /** 读取失败时返回空，首页其余统计照常显示。 */
    public Optional<Visits> visits() {
        try {
            List<Visits> rows = jdbc.query("select total,counted_since from site_visit_counter where id=1",
                    (rs, n) -> new Visits(rs.getLong("total"), rs.getTimestamp("counted_since").toInstant()));
            return rows.stream().findFirst();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    public record Visits(long total, Instant since) {}
}
