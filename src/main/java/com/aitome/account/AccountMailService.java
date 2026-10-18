package com.aitome.account;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/**
 * 账号邮件的模板与投递。申请链接的发送是异步的：HTTP 响应不等待发送结果，
 * 因此账号存在与否、窗口内、发送失败的响应一致（ACCOUNT-P02）。链接前缀来自配置，不取自请求头。
 */
@Service
public class AccountMailService {
    private static final Logger log = LoggerFactory.getLogger(AccountMailService.class);
    static final Duration NOTICE_TIMEOUT = Duration.ofSeconds(3);
    private final AccountMailSender sender;
    private final AccountTokenService tokens;
    private final AccountMailBudget budget;
    private final Clock clock;
    private final String baseUrl;
    private final boolean async;
    private final ZoneId zone;
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50), daemonFactory(), new ThreadPoolExecutor.AbortPolicy());

    public AccountMailService(AccountMailSender sender, AccountTokenService tokens, AccountMailBudget budget, Clock clock,
                              @Value("${finns.public-base-url:}") String baseUrl,
                              @Value("${server.port:8080}") String port,
                              @Value("${finns.mail.async:true}") boolean async,
                              @Value("${finns.access.audit-zone:Asia/Shanghai}") String zone) {
        this.sender = sender; this.tokens = tokens; this.budget = budget; this.clock = clock; this.async = async; this.zone = ZoneId.of(zone);
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = (trimmed.isEmpty() ? "http://127.0.0.1:" + port : trimmed).replaceAll("/+$", "");
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> { Thread thread = new Thread(runnable, "account-mail"); thread.setDaemon(true); return thread; };
    }

    @PreDestroy void shutdown() { executor.shutdownNow(); }

    /** 提交一封链接邮件。发送成功才让新令牌生效；失败只标记新令牌，旧链接保持有效。 */
    public void sendLink(AccountTokenService.Issued issued, boolean unverifiedAccount) {
        Runnable job = () -> {
            try {
                sender.send(linkMail(issued, unverifiedAccount));
                tokens.activate(issued);
                log.info("账号邮件已发送 purpose={} token={} to={}", issued.purpose(), issued.id(), AccountTokenService.emailTag(issued.email()));
            } catch (Exception e) {
                tokens.fail(issued.id());
                log.warn("账号邮件发送失败 purpose={} token={} to={} reason={}", issued.purpose(), issued.id(), AccountTokenService.emailTag(issued.email()), e.getClass().getSimpleName());
            }
        };
        if (!async) { job.run(); return; }
        try { executor.execute(job); }
        catch (RejectedExecutionException full) {
            tokens.fail(issued.id());
            log.warn("账号邮件队列已满 purpose={} token={}", issued.purpose(), issued.id());
        }
    }

    /** 密码更新通知：提交后发送，带 3 秒超时。失败返回 false，不影响已成功的密码更新。 */
    public boolean sendPasswordChangedNotice(String email) {
        budget.noticeAttempted();
        AccountMail mail = new AccountMail(email, "你的 FINNS 密码已更新",
                "你的 FINNS 账号密码已在 " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(clock.instant()) + "（" + zone + "）更新。\n\n"
                + "如果这是你本人的操作，无需处理。\n如果不是你本人操作，请立即在登录页选择“忘记密码”，通过邮箱重置密码。\n\n"
                + "本邮件不包含密码或恢复凭据。");
        try {
            if (!async) { sender.send(mail); return true; }
            Future<?> result = executor.submit(() -> { sender.send(mail); return null; });
            result.get(NOTICE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warn("密码更新通知未送达 to={} reason={}", AccountTokenService.emailTag(email), e.getClass().getSimpleName());
            return false;
        }
    }

    AccountMail linkMail(AccountTokenService.Issued issued, boolean unverifiedAccount) {
        String link = switch (issued.purpose()) {
            case SIGNUP -> baseUrl + "/auth/verify?purpose=signup&token=" + issued.raw();
            case VERIFY -> baseUrl + "/auth/verify?purpose=verify&token=" + issued.raw();
            case RESET -> baseUrl + "/auth/reset?purpose=reset&token=" + issued.raw();
        };
        String subject, intro, validity;
        switch (issued.purpose()) {
            case SIGNUP -> { subject = "验证邮箱，继续创建 FINNS 账号"; intro = "请打开下面的链接验证邮箱，并继续设置昵称和密码。"; validity = "链接24小时内有效。"; }
            case VERIFY -> { subject = "验证你的 FINNS 邮箱"; intro = "请打开下面的链接验证邮箱。验证后你的登录状态不受影响。"; validity = "链接24小时内有效。"; }
            default -> {
                subject = unverifiedAccount ? "验证邮箱并重置密码" : "重置 FINNS 密码";
                intro = unverifiedAccount ? "请打开下面的链接，本次操作会同时完成邮箱验证与密码重置。" : "请打开下面的链接设置新密码。";
                validity = "链接30分钟内有效。完成重置后，所有设备都需要重新登录。";
            }
        }
        String text = "你好，\n\n" + intro + "\n\n" + link + "\n\n" + validity + "\n仅打开链接不会改变任何内容。如果这不是你的操作，请忽略这封邮件。";
        return new AccountMail(issued.email(), subject, text);
    }
}
