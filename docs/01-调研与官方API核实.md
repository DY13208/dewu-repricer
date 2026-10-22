# 调研与官方 API 核实（2026-10-22）

## 参考项目分析
| 项目 | 做法 | 结论 |
|---|---|---|
| shenjianwei/dewuautoputaway（Python） | 调用商家后台 `stark.dewu.com/api/v1/h5/biz/...` 私有接口，复用浏览器 `mchToken`，并在代码/README 中写死签名盐值；附带 chromedriver 自动化 | **不可借鉴实现**：属于非公开接口 + 抓包签名 + 模拟客户端，违反本项目约束，且接口早已不保证可用。只参考了业务概念：出价列表、`curMinPrice`/`myMinPrice`、手续费字段。 |
| Ken-Chy129/shoes（Java） | 多平台（StockX/eBay/得物）运营台；得物部分通过 `openapi.dewu.com/dop/api/v1` + app_key/app_secret 签名 + POP OAuth2（`open.poizon.com .../oauth2/token`）| 参考了：OAuth2 授权 + token 刷新、按参数字典序签名的官方开放平台模式、任务暂停/恢复/明细的设计。未复制其代码；其中出现的第三方查价服务（非得物官方）不采用。 |

## 得物开放平台（open.dewu.com）
- 站点为前端 SPA，文档正文需登录/渲染，本次通过 WebFetch/curl **无法读取接口文档正文**；可确认：需「开发者入驻（企业资质，1-3 个工作日）→ 创建应用并审核（1-3 个工作日）→ 商家授权」。
- 从公开信息可推断存在商品（product/sku、product/price 等文档页）、订单、出价相关的开放 API 分组，网关形如 `https://openapi.dewu.com/dop/api/v1/...`，使用 app_key + 签名 + 商家 access_token。
- **未能核实**（必须在控制台确认后才可开启对应能力开关）：
  1. 是否有“修改出价价格”的官方接口及其权限包、是否异步；
  2. 是否提供“同款最低价/竞争价”查询（如不提供，系统不跟价，不会通过其他手段获取）；
  3. 是否提供官方“自动跟价”开关接口；
  4. 各接口 QPS / 日调用额度。
- 第三方 ISV 文档（如 fw199 网关）显示存在“商家出价列表”等能力，但属第三方转发，接入前需确认其官方授权身份与合规性。

## 结论
系统以官方开放平台为首选，适配器能力全部默认为 `false`，逐项核实后再打开。见 02-可行性矩阵。
