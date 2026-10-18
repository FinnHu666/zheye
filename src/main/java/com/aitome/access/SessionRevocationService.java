package com.aitome.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 按用户撤销全部会话：session_epoch + 1，该用户所有旧会话在下一次请求即失效。
 * 供密码重置使用，也是 FINNS-16（用户停用）联调时的会话契约。
 */
@Service
public class SessionRevocationService {
    private final JdbcTemplate jdbc;

    public SessionRevocationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void revokeAll(long userId) {
        jdbc.update("update user_accounts set session_epoch=session_epoch+1 where id=?", userId);
    }
}
