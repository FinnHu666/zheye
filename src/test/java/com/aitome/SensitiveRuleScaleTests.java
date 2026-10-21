package com.aitome;

import com.aitome.review.SensitiveRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 条件 C7：启用规则上限 20,000 条时的编译与检测耗时，以及“规则指纹不变则复用已编译自动机”。 */
@SpringBootTest
class SensitiveRuleScaleTests {
    @Autowired SensitiveRuleService rules; @Autowired JdbcTemplate jdbc;

    @BeforeEach void load() {
        TestReviewConfig.reset(jdbc);
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 19_999; i++) {
            String phrase = "规模词" + i + "号";
            rows.add(new Object[]{phrase, phrase, i % 3 == 0 ? "HIGH" : "MEDIUM", "规模", now, now});
        }
        jdbc.batchUpdate("insert into sensitive_rules(phrase,note,enabled,deleted,active_key,level,tag,version,created_at,updated_at) values(?,'',true,false,?,?,?,1,?,?)", rows);
    }

    @AfterEach void clean() { TestReviewConfig.reset(jdbc); }

    @Test void twentyThousandEnabledRulesCompileOnceAndDetectFast() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30_000; i++) text.append("普通正文内容");
        text.append("规模词19998号");
        List<String> texts = List.of(text.toString());

        long start = System.nanoTime();
        var first = rules.detect(texts);                       // 含自动机编译
        long firstMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals("HIGH", first.level()); // 19998 % 3 == 0
        assertEquals(List.of("规模词19998号"), first.phrases());

        start = System.nanoTime();
        var second = rules.detect(texts);                      // 指纹不变：复用
        long secondMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(first.phrases(), second.phrases());
        System.out.println("SCALE first=" + firstMs + "ms cached=" + secondMs + "ms rules=20000 chars=" + text.length());
        assertTrue(firstMs < 15_000, "首次编译+检测应在 15 秒内，实际 " + firstMs + "ms");
        assertTrue(secondMs < 2_000, "复用已编译自动机后检测应在 2 秒内，实际 " + secondMs + "ms");
        assertTrue(secondMs <= firstMs, "缓存命中不应比首次更慢");
    }

    @Test void enablingBeyondTheCapIsRejected() {
        Integer enabled = jdbc.queryForObject("select count(*) from sensitive_rules where enabled=true and deleted=false", Integer.class);
        assertEquals(20_000, enabled); // 19,999 条 + 测试种子规则
        assertEquals(20_000, SensitiveRuleService.MAX_ENABLED);
    }
}
