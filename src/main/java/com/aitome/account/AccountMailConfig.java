package com.aitome.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 按 finns.mail.mode 选择实现：disabled（默认）| smtp | outbox。凭据只来自环境变量，不写入仓库或日志。
 */
@Configuration
public class AccountMailConfig {
    @Bean
    AccountMailSender accountMailSender(@Value("${finns.mail.mode:disabled}") String mode,
                                        @Value("${finns.mail.host:}") String host,
                                        @Value("${finns.mail.port:587}") int port,
                                        @Value("${finns.mail.username:}") String username,
                                        @Value("${finns.mail.password:}") String password,
                                        @Value("${finns.mail.from:}") String from,
                                        @Value("${finns.mail.starttls:true}") boolean starttls,
                                        @Value("${finns.mail.ssl:false}") boolean ssl,
                                        @Value("${finns.public-base-url:}") String baseUrl,
                                        @Value("${server.address:}") String serverAddress,
                                        @Value("${finns.mail.outbox-dir:data/dev-mail}") String outboxDir) {
        switch (mode.trim().toLowerCase()) {
            case "smtp" -> {
                if (host.isBlank() || from.isBlank() || baseUrl.isBlank())
                    throw new IllegalStateException("finns.mail.mode=smtp 需要配置 FINNS_MAIL_HOST、FINNS_MAIL_FROM 和 FINNS_PUBLIC_BASE_URL");
                JavaMailSenderImpl sender = new JavaMailSenderImpl();
                sender.setHost(host); sender.setPort(port); sender.setDefaultEncoding("UTF-8");
                if (!username.isBlank()) sender.setUsername(username);
                if (!password.isBlank()) sender.setPassword(password);
                if (!username.isBlank() && !ssl && !starttls)
                    throw new IllegalStateException("配置了 SMTP 账号却关闭了 TLS：请启用 FINNS_MAIL_SSL（465）或 FINNS_MAIL_STARTTLS（587），不允许明文传输凭据");
                sender.getJavaMailProperties().putAll(smtpProperties(ssl, starttls, !username.isBlank()));
                return new SmtpAccountMailSender(sender, from);
            }
            case "outbox" -> {
                if (!isLoopback(serverAddress))
                    throw new IllegalStateException("finns.mail.mode=outbox 仅限本机开发：请同时设置 server.address 为 127.0.0.1");
                return new OutboxAccountMailSender(Path.of(outboxDir));
            }
            case "disabled", "" -> { return new DisabledAccountMailSender(); }
            default -> throw new IllegalStateException("未知的 finns.mail.mode：" + mode);
        }
    }

    /**
     * SMTP 连接属性。隐式 TLS（465）与 STARTTLS（587）二选一，隐式 TLS 优先；STARTTLS 为“必须”，
     * 服务器不支持时报错，而不是悄悄退回明文。两种方式都校验服务器证书的主机名。
     */
    static Properties smtpProperties(boolean ssl, boolean starttls, boolean auth) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", String.valueOf(auth));
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.starttls.enable", "false");
        } else if (starttls) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        if (ssl || starttls) props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.connectiontimeout", "5000"); props.put("mail.smtp.timeout", "10000"); props.put("mail.smtp.writetimeout", "10000");
        return props;
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) return false;
        try { return InetAddress.getByName(address.trim()).isLoopbackAddress(); } catch (Exception e) { return false; }
    }
}
