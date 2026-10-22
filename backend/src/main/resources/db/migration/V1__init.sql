-- 得物多店铺跟价系统 初始化表结构（金额统一以“分”为单位存 BIGINT）
CREATE TABLE shop_groups (
  id BIGSERIAL PRIMARY KEY, name VARCHAR(64) NOT NULL UNIQUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());

CREATE TABLE shops (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  platform VARCHAR(32) NOT NULL DEFAULT 'DEWU',
  adapter_type VARCHAR(32) NOT NULL DEFAULT 'MOCK',        -- MOCK / DEWU_OPEN / THIRD_PARTY
  external_shop_id VARCHAR(64),
  owner_entity VARCHAR(128),                                -- 经营主体，用于同主体防互压
  group_id BIGINT REFERENCES shop_groups(id),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',            -- ACTIVE / PAUSED / DISABLED
  repricing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  scan_interval_seconds INT NOT NULL DEFAULT 600,
  qps_limit NUMERIC(8,2) NOT NULL DEFAULT 1.0,
  config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (platform, external_shop_id));
CREATE INDEX idx_shops_group ON shops(group_id);
CREATE INDEX idx_shops_owner ON shops(owner_entity);

CREATE TABLE shop_authorizations (
  id BIGSERIAL PRIMARY KEY,
  shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
  auth_type VARCHAR(32) NOT NULL,                          -- OAUTH2 / APP_KEY / THIRD_PARTY
  credential_ref VARCHAR(256) NOT NULL,                    -- 指向配置/密钥管理的引用，不落明文
  access_token_enc TEXT, refresh_token_enc TEXT,
  scopes VARCHAR(512),
  expires_at TIMESTAMPTZ,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',           -- PENDING/VALID/EXPIRING/EXPIRED/REVOKED/ERROR
  last_checked_at TIMESTAMPTZ, last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (shop_id, auth_type));
CREATE INDEX idx_auth_status_exp ON shop_authorizations(status, expires_at);

CREATE TABLE app_users (
  id BIGSERIAL PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(128) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'OPERATOR', created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE user_shop_permissions (
  user_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
  can_write BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY (user_id, shop_id));

CREATE TABLE products (
  id BIGSERIAL PRIMARY KEY,
  platform VARCHAR(32) NOT NULL DEFAULT 'DEWU',
  spu_id VARCHAR(64) NOT NULL,
  article_number VARCHAR(128), title VARCHAR(512), brand VARCHAR(128), category VARCHAR(128), image_url VARCHAR(512),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (platform, spu_id));
CREATE INDEX idx_products_article ON products(article_number);

CREATE TABLE product_skus (
  id BIGSERIAL PRIMARY KEY,
  product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  sku_id VARCHAR(64) NOT NULL,
  spec VARCHAR(256), barcode VARCHAR(64),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (product_id, sku_id));
CREATE UNIQUE INDEX uk_sku_platform_id ON product_skus(sku_id);

CREATE TABLE shop_sku_listings (
  id BIGSERIAL PRIMARY KEY,
  shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
  sku_pk BIGINT NOT NULL REFERENCES product_skus(id),
  external_listing_id VARCHAR(64),                         -- 出价单号等
  current_price BIGINT, stock INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ON_SALE',
  repricing_excluded BOOLEAN NOT NULL DEFAULT FALSE,       -- 指定 SKU 不参与
  min_profit BIGINT,                                       -- 每 SKU 利润底线（分）
  anomaly_flag VARCHAR(64),
  last_synced_at TIMESTAMPTZ, last_repriced_at TIMESTAMPTZ,
  version INT NOT NULL DEFAULT 0,
  UNIQUE (shop_id, sku_pk),
  UNIQUE (shop_id, external_listing_id));
CREATE INDEX idx_listing_shop_status ON shop_sku_listings(shop_id, status);
CREATE INDEX idx_listing_anomaly ON shop_sku_listings(anomaly_flag) WHERE anomaly_flag IS NOT NULL;

CREATE TABLE sku_costs (
  id BIGSERIAL PRIMARY KEY,
  shop_id BIGINT REFERENCES shops(id) ON DELETE CASCADE,  -- NULL 表示全局成本
  sku_pk BIGINT NOT NULL REFERENCES product_skus(id),
  purchase_cost BIGINT NOT NULL, extra_cost BIGINT NOT NULL DEFAULT 0,
  source VARCHAR(32) NOT NULL DEFAULT 'EXCEL', import_batch VARCHAR(64),
  effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_cost_sku ON sku_costs(sku_pk, shop_id, effective_from DESC);

CREATE TABLE pricing_strategies (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  shop_id BIGINT REFERENCES shops(id),                     -- NULL 为全局模板
  mode VARCHAR(16) NOT NULL,                               -- BELOW / MATCH / ABOVE
  offset_type VARCHAR(16) NOT NULL DEFAULT 'AMOUNT',       -- AMOUNT / PERCENT
  offset_value NUMERIC(12,4) NOT NULL DEFAULT 0,
  min_profit BIGINT, min_price BIGINT, max_price BIGINT,
  fee_rate NUMERIC(6,4) NOT NULL DEFAULT 0.0,              -- 平台技术服务费率（以实际合同为准）
  fee_min BIGINT NOT NULL DEFAULT 0, fee_max BIGINT,
  fulfillment_fee BIGINT NOT NULL DEFAULT 0,               -- 履约/查验/包装等固定费用
  max_change_ratio NUMERIC(6,4) NOT NULL DEFAULT 0.15,     -- 单次变化超过即熔断
  price_step BIGINT NOT NULL DEFAULT 100,                  -- 价格取整步长（分）
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (shop_id, name));

CREATE TABLE strategy_assignments (
  id BIGSERIAL PRIMARY KEY,
  strategy_id BIGINT NOT NULL REFERENCES pricing_strategies(id) ON DELETE CASCADE,
  shop_id BIGINT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
  scope VARCHAR(16) NOT NULL,                              -- SHOP / PRODUCT / LISTING
  target_id BIGINT,                                        -- product_id 或 listing_id
  priority INT NOT NULL DEFAULT 0,
  UNIQUE (shop_id, scope, target_id));

CREATE TABLE competitor_price_snapshots (
  id BIGSERIAL PRIMARY KEY,
  shop_id BIGINT NOT NULL REFERENCES shops(id),
  sku_pk BIGINT NOT NULL REFERENCES product_skus(id),
  lowest_price BIGINT, lowest_is_own_entity BOOLEAN NOT NULL DEFAULT FALSE,
  source VARCHAR(32) NOT NULL, raw_json JSONB,
  captured_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_snap_sku_time ON competitor_price_snapshots(sku_pk, captured_at DESC);

CREATE TABLE repricing_tasks (
  id BIGSERIAL PRIMARY KEY,
  task_type VARCHAR(16) NOT NULL,                          -- SCHEDULED / MANUAL / BATCH
  shop_id BIGINT REFERENCES shops(id),
  priority INT NOT NULL DEFAULT 5,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',           -- PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED
  dry_run BOOLEAN NOT NULL DEFAULT TRUE,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  total INT NOT NULL DEFAULT 0, succeeded INT NOT NULL DEFAULT 0, failed INT NOT NULL DEFAULT 0, skipped INT NOT NULL DEFAULT 0,
  created_by VARCHAR(64), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ);
CREATE INDEX idx_task_status ON repricing_tasks(status, priority DESC, created_at);

CREATE TABLE repricing_task_items (
  id BIGSERIAL PRIMARY KEY,
  task_id BIGINT NOT NULL REFERENCES repricing_tasks(id) ON DELETE CASCADE,
  shop_id BIGINT NOT NULL REFERENCES shops(id),
  listing_id BIGINT NOT NULL REFERENCES shop_sku_listings(id),
  old_price BIGINT, target_price BIGINT, competitor_price BIGINT,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',           -- PENDING/SUBMITTED/VERIFIED/SKIPPED/FAILED/BLOCKED
  decision_reason VARCHAR(256), attempts INT NOT NULL DEFAULT 0, next_retry_at TIMESTAMPTZ,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  external_request_id VARCHAR(128), last_error TEXT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (task_id, listing_id));
CREATE INDEX idx_item_status_retry ON repricing_task_items(status, next_retry_at);
CREATE INDEX idx_item_shop ON repricing_task_items(shop_id);

CREATE TABLE repricing_history (
  id BIGSERIAL PRIMARY KEY,
  shop_id BIGINT NOT NULL REFERENCES shops(id),
  listing_id BIGINT NOT NULL REFERENCES shop_sku_listings(id),
  task_item_id BIGINT REFERENCES repricing_task_items(id),
  old_price BIGINT, new_price BIGINT, verified_price BIGINT, competitor_price BIGINT,
  strategy_id BIGINT, reason VARCHAR(256), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_hist_listing_time ON repricing_history(listing_id, created_at DESC);
CREATE INDEX idx_hist_shop_time ON repricing_history(shop_id, created_at DESC);

CREATE TABLE api_call_logs (
  id BIGSERIAL PRIMARY KEY,
  shop_id BIGINT, adapter VARCHAR(32) NOT NULL, operation VARCHAR(64) NOT NULL,
  request_id VARCHAR(128), http_status INT, success BOOLEAN NOT NULL, latency_ms INT,
  error_code VARCHAR(64), error_msg TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_api_log_shop_time ON api_call_logs(shop_id, created_at DESC);

CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY, actor VARCHAR(64) NOT NULL, action VARCHAR(64) NOT NULL,
  shop_id BIGINT, target_type VARCHAR(32), target_id VARCHAR(64), detail JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_audit_time ON audit_logs(created_at DESC);

CREATE TABLE system_switches (
  name VARCHAR(64) PRIMARY KEY, value VARCHAR(64) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO system_switches(name, value) VALUES ('GLOBAL_REPRICING','PAUSED'), ('EMERGENCY_STOP','OFF');
