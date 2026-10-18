package com.aitome.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 仅供本机开发/验收：把邮件追加写入 data/dev-mail/outbox.jsonl（data 目录已被 git 忽略）。
 * 文件包含可直接使用的链接，因此只允许在服务仅监听回环地址时启用（见 {@link AccountMailConfig}）。
 */
public class OutboxAccountMailSender implements AccountMailSender {
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    public OutboxAccountMailSender(Path directory) {
        this.file = directory.resolve("outbox.jsonl");
        try { Files.createDirectories(directory); } catch (IOException e) { throw new IllegalStateException("无法创建开发出信箱目录", e); }
    }

    @Override
    public synchronized void send(AccountMail mail) throws MailDeliveryException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", Instant.now().toString()); row.put("to", mail.to()); row.put("subject", mail.subject()); row.put("text", mail.text());
        try {
            Files.writeString(file, json.writeValueAsString(row) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new MailDeliveryException("无法写入开发出信箱", e); }
    }
}
