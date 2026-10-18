package com.aitome.account;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** SMTP 连接方式：隐式 TLS（465）与 STARTTLS（587）二选一，STARTTLS 必须成功，不允许明文传输凭据。 */
class AccountMailConfigTests {
    private final AccountMailConfig config = new AccountMailConfig();

    private AccountMailSender build(String mode, String host, String username, boolean starttls, boolean ssl, String baseUrl, String serverAddress) {
        return config.accountMailSender(mode, host, 587, username, "secret", "FINNS <no-reply@example.org>", starttls, ssl, baseUrl, serverAddress, "target/test-outbox");
    }

    @Test void implicitTlsTakesPrecedenceAndChecksTheServerName() {
        Properties props = AccountMailConfig.smtpProperties(true, true, true);
        assertEquals("true", props.get("mail.smtp.ssl.enable"));
        assertEquals("false", props.get("mail.smtp.starttls.enable"));
        assertNull(props.get("mail.smtp.starttls.required"));
        assertEquals("true", props.get("mail.smtp.ssl.checkserveridentity"));
        assertEquals("true", props.get("mail.smtp.auth"));
    }

    @Test void starttlsIsRequiredNotOpportunistic() {
        Properties props = AccountMailConfig.smtpProperties(false, true, true);
        assertEquals("true", props.get("mail.smtp.starttls.enable"));
        assertEquals("true", props.get("mail.smtp.starttls.required"));
        assertNull(props.get("mail.smtp.ssl.enable"));
        assertEquals("true", props.get("mail.smtp.ssl.checkserveridentity"));
    }

    @Test void smtpModeRefusesCredentialsWithoutTlsAndMissingSettings() {
        assertInstanceOf(SmtpAccountMailSender.class, build("smtp", "smtp.example.org", "user", true, false, "https://example.org", ""));
        assertInstanceOf(SmtpAccountMailSender.class, build("smtp", "smtp.example.org", "user", false, true, "https://example.org", ""));
        assertThrows(IllegalStateException.class, () -> build("smtp", "smtp.example.org", "user", false, false, "https://example.org", ""));
        assertThrows(IllegalStateException.class, () -> build("smtp", "", "user", true, false, "https://example.org", ""));
        assertThrows(IllegalStateException.class, () -> build("smtp", "smtp.example.org", "user", true, false, "", ""));
    }

    @Test void outboxIsLoopbackOnlyAndDisabledIsTheDefault() {
        assertThrows(IllegalStateException.class, () -> build("outbox", "", "", true, false, "", ""));
        assertThrows(IllegalStateException.class, () -> build("outbox", "", "", true, false, "", "0.0.0.0"));
        assertInstanceOf(OutboxAccountMailSender.class, build("outbox", "", "", true, false, "", "127.0.0.1"));
        assertInstanceOf(DisabledAccountMailSender.class, build("disabled", "", "", true, false, "", ""));
        assertThrows(IllegalStateException.class, () -> build("carrier-pigeon", "", "", true, false, "", ""));
    }
}
