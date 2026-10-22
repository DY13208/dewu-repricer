package com.dewu.repricer.adapter.dewu;

import java.time.Instant;

/** 解密后的店铺凭据（仅在内存中使用，禁止打日志）。 */
public record DewuCredential(long shopId, Mode mode, String appKey, String appSecret,
                             String accessToken, String refreshToken, String openId,
                             Instant accessExpiresAt, Instant refreshExpiresAt, int biddingType) {
    public enum Mode { SELF_DEVELOPED, ISV }
    @Override public String toString() { return "DewuCredential[shop=" + shopId + ", mode=" + mode + ", appKey=" + appKey + "]"; }
}
