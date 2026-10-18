package com.aitome.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局日发送预算（概设 §3.3.1）：免费邮件服务商的日额度是共享资源，攻击者用大量不同邮箱就能耗尽它。
 * 预算 = 当天（审计时区）已发出的令牌邮件 + 在途发送 + 通知邮件（进程内计数，重启清零，保守估计）。
 * 用尽时新的令牌邮件按“发送失败”处理（公开响应不变，旧链接保持有效）；通知邮件不被拒绝但计入，
 * 因此预算应低于服务商日额度（建议 80%）。finns.mail.daily-budget 为 0 表示不限制。
 */
@Component
public class AccountMailBudget {
    private static final Logger log = LoggerFactory.getLogger(AccountMailBudget.class);
    private final AccountTokenService tokens;
    private final Clock clock;
    private final ZoneId zone;
    private final int budget;
    private final AtomicInteger notices = new AtomicInteger();
    private volatile LocalDate noticeDay;
    private volatile LocalDate warnedDay;

    public AccountMailBudget(AccountTokenService tokens, Clock clock,
                             @Value("${finns.mail.daily-budget:0}") int budget,
                             @Value("${finns.access.audit-zone:Asia/Shanghai}") String zone) {
        this.tokens = tokens; this.clock = clock; this.budget = Math.max(0, budget); this.zone = ZoneId.of(zone);
    }

    public boolean enabled() { return budget > 0; }

    /** 新的令牌邮件是否还在预算内。达到预算 80% 时当天记一次 WARN；超出时记 WARN 并返回 false。 */
    public boolean allowNewLinkMail() {
        if (!enabled()) return true;
        int used = usedToday();
        if (used >= budget) {
            log.warn("账号邮件日预算已用尽 used={} budget={}，新的验证/重置邮件按发送失败处理", used, budget);
            return false;
        }
        LocalDate today = today();
        if (used * 10 >= budget * 8 && !today.equals(warnedDay)) {
            warnedDay = today;
            log.warn("账号邮件日预算已用 80% 以上 used={} budget={}", used, budget);
        }
        return true;
    }

    /** 记一次通知邮件的发送尝试（不论成败，保守计入）。 */
    public void noticeAttempted() {
        synchronized (notices) { rollNoticeDay(); notices.incrementAndGet(); }
    }

    int usedToday() {
        Integer sent = tokens.sentSince(today().atStartOfDay(zone).toInstant());
        synchronized (notices) { rollNoticeDay(); return (sent == null ? 0 : sent) + notices.get(); }
    }

    private LocalDate today() { return clock.instant().atZone(zone).toLocalDate(); }

    private void rollNoticeDay() {
        LocalDate today = today();
        if (!today.equals(noticeDay)) { noticeDay = today; notices.set(0); }
    }
}
