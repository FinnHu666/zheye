package com.aitome.account;

/** 邮件发送端口。实现：SMTP（生产）、本机出信箱（开发/验收）、禁用（默认）。 */
public interface AccountMailSender {
    /** 失败时抛出 {@link MailDeliveryException}，调用方据此保留旧链接。 */
    void send(AccountMail mail) throws MailDeliveryException;

    class MailDeliveryException extends Exception {
        public MailDeliveryException(String message) { super(message); }
        public MailDeliveryException(String message, Throwable cause) { super(message, cause); }
    }
}
