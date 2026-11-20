package com.example.chat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwoFactorServiceTest {

    private final TwoFactorService service = new TwoFactorService();

    @Test
    void generatesBase32Secret() {
        String secret = service.generateSecret();
        assertThat(secret).hasSize(32).matches("^[A-Z2-7]+$");
        assertThat(service.generateSecret()).isNotEqualTo(secret);
    }

    @Test
    void qrCodeIsAPngDataUri() {
        String uri = service.getQrCodeDataUri(service.generateSecret(), "alice");
        assertThat(uri).startsWith("data:image/png;base64,");
        assertThat(uri.length()).isGreaterThan(500); // a real QR image, not an empty stub
    }

    @Test
    void verifiesCurrentCodeAndRejectsWrongOnes() {
        String secret = service.generateSecret();
        String current = service.currentCode(secret);
        assertThat(service.verifyCode(secret, current)).isTrue();

        String wrong = String.format("%06d", (Integer.parseInt(current) + 500000) % 1000000);
        assertThat(service.verifyCode(secret, wrong)).isFalse();
        assertThat(service.verifyCode(secret, null)).isFalse();
        assertThat(service.verifyCode(null, current)).isFalse();
    }
}
