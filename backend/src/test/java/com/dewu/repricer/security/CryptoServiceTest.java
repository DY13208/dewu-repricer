package com.dewu.repricer.security;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class CryptoServiceTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    @Test void roundTripAndRandomIv() {
        var c = new CryptoService(KEY);
        String a = c.encrypt("secret-值"), b = c.encrypt("secret-值");
        assertThat(a).startsWith("v1:").isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo("secret-值");
    }
    @Test void wrongKeyFails() {
        String enc = new CryptoService(KEY).encrypt("x");
        byte[] k = new byte[32]; k[0] = 1;
        assertThatThrownBy(() -> new CryptoService(Base64.getEncoder().encodeToString(k)).decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingKeyRefuses() {
        assertThatThrownBy(() -> new CryptoService("").encrypt("x")).hasMessageContaining("REPRICER_CRYPTO_KEY");
    }
}
