package com.dewu.repricer.adapter.dewu;

import com.dewu.repricer.security.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcDewuCredentialStore implements DewuCredentialStore {
    private final JdbcTemplate jdbc; private final CryptoService crypto;
    public JdbcDewuCredentialStore(JdbcTemplate jdbc, CryptoService crypto) { this.jdbc = jdbc; this.crypto = crypto; }

    @Override public Optional<DewuCredential> find(long shopId) {
        return jdbc.query("SELECT * FROM shop_authorizations WHERE shop_id=? ORDER BY id DESC LIMIT 1", (rs, n) -> new DewuCredential(
                shopId, DewuCredential.Mode.valueOf(rs.getString("auth_mode")), rs.getString("app_key"),
                crypto.decrypt(rs.getString("app_secret_enc")), crypto.decrypt(rs.getString("access_token_enc")),
                crypto.decrypt(rs.getString("refresh_token_enc")), rs.getString("open_id"),
                ts(rs.getTimestamp("expires_at")), ts(rs.getTimestamp("refresh_expires_at")), rs.getInt("bidding_type")), shopId).stream().findFirst();
    }
    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    /** 保存/更新店铺凭据（自研应用：app_key + app_secret；ISV：仅模式，token 走 OAuth）。 */
    public void saveCredential(long shopId, DewuCredential.Mode mode, String appKey, String appSecret, int biddingType) {
        String authType = mode == DewuCredential.Mode.ISV ? "OAUTH2" : "APP_KEY";
        jdbc.update("""
            INSERT INTO shop_authorizations(shop_id,auth_type,credential_ref,auth_mode,app_key,app_secret_enc,bidding_type,status)
            VALUES (?,?,?,?,?,?,?, ?)
            ON CONFLICT (shop_id, auth_type) DO UPDATE SET auth_mode=EXCLUDED.auth_mode, app_key=EXCLUDED.app_key,
              app_secret_enc=COALESCE(EXCLUDED.app_secret_enc, shop_authorizations.app_secret_enc), bidding_type=EXCLUDED.bidding_type,
              status=EXCLUDED.status, last_error=NULL""",
                shopId, authType, "db:shop_authorizations", mode.name(), appKey, appSecret == null ? null : crypto.encrypt(appSecret), biddingType,
                mode == DewuCredential.Mode.SELF_DEVELOPED ? "VALID" : "PENDING");
        jdbc.update("DELETE FROM shop_authorizations WHERE shop_id=? AND auth_type<>?", shopId, authType);
    }
    @Override public void saveTokens(long shopId, String at, String rt, String openId, Instant exp, Instant rexp) {
        jdbc.update("""
            UPDATE shop_authorizations SET access_token_enc=?, refresh_token_enc=COALESCE(?, refresh_token_enc), open_id=COALESCE(?, open_id),
              expires_at=?, refresh_expires_at=COALESCE(?, refresh_expires_at), status='VALID', last_checked_at=now(), last_error=NULL
            WHERE shop_id=?""", crypto.encrypt(at), rt == null ? null : crypto.encrypt(rt), openId,
                exp == null ? null : Timestamp.from(exp), rexp == null ? null : Timestamp.from(rexp), shopId);
    }
    @Override public void markError(long shopId, String status, String error) {
        jdbc.update("UPDATE shop_authorizations SET status=?, last_error=?, last_checked_at=now() WHERE shop_id=?", status, error, shopId);
    }
}
