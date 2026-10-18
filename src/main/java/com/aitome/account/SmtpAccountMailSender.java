package com.aitome.account;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

public class SmtpAccountMailSender implements AccountMailSender {
    private final JavaMailSender sender;
    private final String from;

    public SmtpAccountMailSender(JavaMailSender sender, String from) { this.sender = sender; this.from = from; }

    @Override
    public void send(AccountMail mail) throws MailDeliveryException {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), false);
            sender.send(message);
        } catch (Exception e) {
            throw new MailDeliveryException("SMTP 发送失败：" + e.getClass().getSimpleName(), e); // 不携带收件人或服务商回执正文
        }
    }
}
