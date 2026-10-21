package com.aitome;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/** FINNS-17 之后检测未配置会阻止发布（P03）。测试库启动时放一条不会误命中的中性规则，让既有发布测试照常进行。 */
@Configuration
class TestReviewConfig {
    static final String SEED_PHRASE = "zzseedrule";

    @Bean ApplicationRunner seedNeutralSensitiveRule(JdbcTemplate jdbc) {
        return args -> reset(jdbc);
    }

    /** 让指定专题视为已通过首次审批，供与审批无关的发布测试直接使用（等同历史已发布专题）。 */
    static void preapprove(JdbcTemplate jdbc, long collectionId) {
        Long owner = jdbc.queryForObject("select owner_id from community_collections where id=?", Long.class, collectionId);
        Integer exists = jdbc.queryForObject("select count(*) from review_content_state where content_type='COLLECTION' and target_id=?", Integer.class, collectionId);
        if (exists != null && exists > 0) return;
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into review_content_state(author_id,content_type,content_key,target_id,attempts,generation,approved_once,legacy,created_at,updated_at) values(?,?,?,?,0,1,true,true,?,?)",
                owner, "COLLECTION", "COLLECTION:" + collectionId, collectionId, now, now);
    }

    static void reset(JdbcTemplate jdbc) {
        jdbc.update("delete from review_flow_events");
        jdbc.update("delete from review_flows");
        jdbc.update("delete from review_noted");
        jdbc.update("delete from review_receipts");
        jdbc.update("delete from review_submissions");
        jdbc.update("delete from review_content_state");
        jdbc.update("delete from review_audit_log");
        jdbc.update("delete from sensitive_rules");
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,version,created_at,updated_at) values(?,?,true,false,?,1,?,?)", SEED_PHRASE, "测试中性规则", SEED_PHRASE, now, now);
    }
}
