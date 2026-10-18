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
                Properties props = sender.getJavaMailProperties();
                props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
                props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
                props.put("mail.smtp.connectiontimeout", "5000"); props.put("mail.smtp.timeout", "10000"); props.put("mail.smtp.writetimeout", "10000");
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

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) return false;
        try { return InetAddress.getByName(address.trim()).isLoopbackAddress(); } catch (Exception e) { return false; }
    }
}
