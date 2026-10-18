package com.aitome;

import com.aitome.account.AccountMail;
import com.aitome.account.AccountMailSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 测试替身：可调时钟（冷却/过期）和记录邮件的发送端口（读取验证链接、模拟发送失败）。被组件扫描自动加载。 */
@Configuration
class TestAccountConfig {
    @Bean @Primary MutableClock testAccountClock() { return new MutableClock(); }
    @Bean @Primary RecordingMailSender testAccountMailSender() { return new RecordingMailSender(); }

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();
        void reset() { now = Instant.now(); }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static final class RecordingMailSender implements AccountMailSender {
        private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");
        final List<AccountMail> sent = new CopyOnWriteArrayList<>();
        volatile boolean failing;
        volatile boolean failNextOnly;

        void reset() { sent.clear(); failing = false; failNextOnly = false; }

        @Override public void send(AccountMail mail) throws MailDeliveryException {
            if (failing || failNextOnly) { failNextOnly = false; throw new MailDeliveryException("test failure"); }
            sent.add(mail);
        }

        List<AccountMail> to(String email) { return sent.stream().filter(m -> m.to().equalsIgnoreCase(email)).toList(); }

        /** 发给该邮箱的最近一封邮件中的令牌。 */
        String lastToken(String email) {
            List<AccountMail> mails = to(email);
            if (mails.isEmpty()) throw new AssertionError("no mail sent to " + email);
            Matcher m = TOKEN.matcher(mails.get(mails.size() - 1).text());
            if (!m.find()) throw new AssertionError("no token in mail to " + email);
            return m.group(1);
        }
    }
}
