package com.aitome.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 启动初始化（幂等）：H2 开发库没有 Flyway，首次启动时把已存在且未验证的账号标记为旧账号，
 * 之后不再执行；PostgreSQL 的 V13 已在迁移时写入同一标记，这里不做任何事。
 * 不会把任何历史邮箱标记为已验证。
 */
@Component
public class AccountBootstrap {
    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AccountBootstrap(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() { tx.executeWithoutResult(status -> run()); }

    void run() {
        Integer marked = jdbc.queryForObject("select count(*) from account_state where id=1", Integer.class);
        if (marked != null && marked > 0) return;
        int legacy = jdbc.update("update user_accounts set legacy_account=true where email_verified_at is null");
        jdbc.update("insert into account_state(id,legacy_marked_at) values(1,current_timestamp)");
        log.info("账号过渡标记完成：{} 个既有账号保持可登录并提示补验证", legacy);
    }
}
