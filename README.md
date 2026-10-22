# 得物多店铺、多 SKU 自动跟价管理系统

> ⚠️ **重要声明：真实得物接口尚未接入。** 当前只有 Mock 适配器可执行完整流程；`DEWU_OPEN` / `THIRD_PARTY` 适配器仅为骨架，所有调用返回“未接入”且不会改价。系统**不能**用于正式自动改价，直到完成开放平台入驻、权限核实与《docs/05-小规模真实改价测试方案.md》中的全部步骤。系统默认全局 `DRY-RUN`、全局跟价 `PAUSED`。

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
# 后台 http://localhost   指标 http://localhost:8080/actuator/prometheus
```
本地开发：`cd backend && mvn spring-boot:run`（需本地 PostgreSQL/Redis，可加 `--repricer.queue=memory` 不用 Redis）；`cd frontend && npm i && npm run dev`。

Mock 体验流程：店铺管理 → 绑定店铺（适配器 MOCK，填写经营主体）→ 授权 → 同步商品 → 规则配置新建策略并分配整店 → SKU 列表“模拟调价” → 任务记录查看明细。

## 测试
`cd backend && mvn test`（引擎、费用、风控、队列、Mock 适配器、执行器共 30 个单测）；`cd frontend && npm run build`。

更多见 docs/。
