package com.dewu.repricer.adapter.dewu;

import java.time.Instant;
import java.util.Optional;

public interface DewuCredentialStore {
    Optional<DewuCredential> find(long shopId);
    void saveTokens(long shopId, String accessToken, String refreshToken, String openId, Instant accessExpiresAt, Instant refreshExpiresAt);
    void markError(long shopId, String status, String error);
}
