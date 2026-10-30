package com.moveinsync.config.server.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    @Test
    void masksEmailsAndPhoneNumbersButKeepsConfigValues() {
        assertThat(PiiRedactor.redact("asked by priya.k@acme.co.in on +91 98765 43210 for blr-1"))
                .isEqualTo("asked by [email] on [phone] for blr-1");
        assertThat(PiiRedactor.redact("cut-off PT30M at 22:00-06:00, pool 4, version 1234")).doesNotContain("[phone]");
        assertThat(PiiRedactor.redact(null)).isNull();
    }
}
