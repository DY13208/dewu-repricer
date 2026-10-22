# 得物多店铺、多 SKU 自动跟价管理系统

> ⚠️ **重要声明：系统尚不能用于正式自动改价。**
> - 得物开放平台真实适配器（`DEWU_OPEN`）已按官方公开文档（docs/07）实现签名、网关、授权、出价查询、改价、回查、官方自动跟价，并通过 WireMock 契约测试；**但尚未用真实凭据在沙箱/生产联调**，响应字段、参数位置、token 失效错误码等均可能与实际不同。
> - **最低价（竞争价）接口参数待控制台核实**，默认关闭（`DEWU_LOWEST_PRICE_ENABLED=false`）；关闭时引擎拿不到竞争价，不会跟价。
> - 能力由已获批权限包（`DEWU_GRANTED_PACKS`）决定，默认为空 = 无任何能力。
> - 必须先取得应用凭据并申请到权限包，再严格按《docs/05-小规模真实改价测试方案.md》做小规模测试。`THIRD_PARTY` 仍为骨架。
> - 系统默认全局 `DRY-RUN`、全局跟价 `PAUSED`。

## 技术栈
Java 21 + Spring Boot 3.3（JDBC、Quartz、Actuator/Prometheus）· PostgreSQL + Flyway · Redis（ZSET 优先级/延迟队列）· Vue 3 + Element Plus · Docker Compose

## 目录
```
backend/   后端（adapter 适配层 / engine 跟价引擎 / risk 风控 / task 任务执行 / web 接口）
frontend/  运营后台
docs/      调研、可行性矩阵、设计、测试方案、部署验收
deploy/    Prometheus 配置
```

## 快速开始
```bash
cp .env.example .env      # 修改密码
docker compose up -d --build
# 后台 http://localhost:18081   后端/指标 http://localhost:18080/actuator/prometheus
```
本地开发：`cd backend && mvn spring-boot:run`（需本地 PostgreSQL/Redis，可加 `--repricer.queue=memory` 不用 Redis）；`cd frontend && npm i && npm run dev`。

Mock 体验流程：店铺管理 → 绑定店铺（适配器 MOCK，填写经营主体）→ 授权 → 同步商品 → 规则配置新建策略并分配整店 → SKU 列表“模拟调价” → 任务记录查看明细。

## 测试
`cd backend && mvn test`（共 55 个：引擎/费用/900 价格规则、风控、队列、执行器、AES 加密、签名、得物适配器 WireMock 契约测试）；`cd frontend && npm run build`。

## 得物开放平台接入配置
1. `.env` 设置 `REPRICER_CRYPTO_KEY`（`openssl rand -base64 32`）、`DEWU_ENV=SANDBOX`、`DEWU_GRANTED_PACKS`。
2. 自研商家应用（1 应用 = 1 店铺）：后台“店铺管理 → 凭据”录入该店 app_key/app_secret（AES-GCM 加密入库）。
3. ISV：`.env` 配置 `DEWU_ISV_APP_KEY/SECRET`，店铺凭据选 ISV，再“授权”填入 OAuth code；access_token 提前 30 分钟自动刷新，遇 token 失效码刷新后重试一次。
4. 策略选“得物：价格以 900 结尾”的价格规则。

更多见 docs/（07 为官方接口核实，08 为真实适配器实现说明）。
