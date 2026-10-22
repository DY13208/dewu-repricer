package com.dewu.repricer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加密：AES-256-GCM。密钥来自环境变量 REPRICER_CRYPTO_KEY（Base64 编码的 32 字节），不落库不入仓。
 * 密文格式：v1:Base64(12字节IV || 密文+Tag)。
 */
@Component
public class CryptoService {
    private static final String PREFIX = "v1:";
    private final SecretKeySpec key;
    private final SecureRandom rnd = new SecureRandom();

    public CryptoService(@Value("${repricer.crypto-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) { key = null; return; }
        byte[] k = Base64.getDecoder().decode(base64Key.trim());
        if (k.length != 32) throw new IllegalArgumentException("REPRICER_CRYPTO_KEY 必须是 Base64 编码的 32 字节密钥");
        key = new SecretKeySpec(k, "AES");
    }
    public boolean enabled() { return key != null; }

    public String encrypt(String plain) {
        if (plain == null) return null;
        requireKey();
        try {
            byte[] iv = new byte[12]; rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
        } catch (Exception e) { throw new IllegalStateException("加密失败", e); }
    }
    public String decrypt(String enc) {
        if (enc == null) return null;
        if (!enc.startsWith(PREFIX)) throw new IllegalStateException("凭据未加密或格式不正确");
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(enc.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("解密失败（密钥不匹配？）", e); }
    }
    private void requireKey() { if (key == null) throw new IllegalStateException("未配置 REPRICER_CRYPTO_KEY，无法加解密店铺凭据"); }
}
