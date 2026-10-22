-- 得物开放平台真实适配器：凭据与价格规则
ALTER TABLE shop_authorizations
  ADD COLUMN auth_mode VARCHAR(16) NOT NULL DEFAULT 'ISV',      -- SELF_DEVELOPED（自研商家应用，每店一套 app_key/secret）/ ISV（OAuth）
  ADD COLUMN app_key VARCHAR(128),
  ADD COLUMN app_secret_enc TEXT,                               -- AES-GCM 加密
  ADD COLUMN open_id VARCHAR(128),
  ADD COLUMN refresh_expires_at TIMESTAMPTZ,
  ADD COLUMN bidding_type INT NOT NULL DEFAULT 0;               -- 0 普通现货；其他走 generic 接口
ALTER TABLE pricing_strategies ADD COLUMN price_rule VARCHAR(16) NOT NULL DEFAULT 'NONE';  -- DEWU_900
CREATE TABLE api_call_counters (
  shop_id BIGINT NOT NULL, api VARCHAR(128) NOT NULL, bucket VARCHAR(16) NOT NULL,  -- yyyyMMdd 或 yyyyMMddHHmm
  calls INT NOT NULL DEFAULT 0, PRIMARY KEY (shop_id, api, bucket));
