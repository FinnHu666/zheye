package com.aitome.account;

/** 一封纯文本账号邮件。不含密码、旧密码或其他账号数据。 */
public record AccountMail(String to, String subject, String text) {}
