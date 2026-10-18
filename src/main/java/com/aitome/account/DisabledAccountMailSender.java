package com.aitome.account;

/** 默认实现：不发送、不记录链接。邮件通道未配置时账号创建不可用，而不是悄悄跳过验证。 */
public class DisabledAccountMailSender implements AccountMailSender {
    @Override
    public void send(AccountMail mail) throws MailDeliveryException { throw new MailDeliveryException("邮件通道未启用"); }
}
