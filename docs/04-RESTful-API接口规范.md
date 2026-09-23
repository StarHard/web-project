# 气象观测站嵌入式数据采集与 Web 预报系统 — RESTful API 接口规范

| 文档属性 | 内容 |
|---|---|
| 版本 | v1.0 |
| 编写日期 | 2026-09-16 |
| 基础路径 | `/api/v1` |

---

## 1. 全局约定

### 1.1 协议与格式
- HTTPS + JSON；请求/响应 `Content-Type: application/json; charset=UTF-8`。
- 时间格式：`yyyy-MM-dd HH:mm:ss`；时间范围参数 `startTime/endTime`。
- 认证：除白名单（登录、公开实时数据、科普）外均需请求头 `Authorization: Bearer <accessToken>`。

### 1.2 统一响应结构

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": 1760000000000
}
```

| 字段 | 说明 |
|---|---|
| code | 0 成功；非 0 为错误码（见 1.4） |
| message | 提示信息，可直接展示 |
| data | 业务数据；分页时固定为 PageResult 结构 |
| timestamp | 服务器毫秒时间戳 |

### 1.3 分页请求与响应

请求参数：`pageNum`（默认 1）、`pageSize`（默认 20，最大 500）。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "total": 156,
    "pageNum": 1,
    "pageSize": 20,
    "list": []
  }
}
```

### 1.4 错误码规范

| 区间 | 含义 | 示例 |
|---|---|---|
| 0 | 成功 | — |
| 400xx | 通用请求错误 | 40001 参数校验失败；40004 资源不存在 |
| 401xx | 认证失败 | 40101 未登录/Token 过期 |
| 403xx | 权限不足 | 40301 无访问权限 |
| 500xx | 系统错误 | 50001 数据库异常；50002 消息队列异常 |
| 60xxx | 业务错误 | 60001 站点编码已存在；60002 告警规则阈值非法 |

### 1.5 HTTP 方法与资源命名
- 资源名用复数名词 kebab-case：`/stations`、`/alert-rules`、`/qc-tasks`。
- GET 查询 / POST 新增 / PUT 全量修改 / PATCH 状态或局部修改 / DELETE 删除（逻辑删除）。
- 操作类非 CRUD 动作用动词子路径：`POST /api/v1/stations/{id}/online-check`。

### 1.6 站点标识约定

| 场景 | 参数名 | 类型 | 取值 |
|---|---|---|---|
| 时序数据接口（`/realtime/**`、`/forecasts/**`） | `stationCode` | String | 站点编码，如 `CAMPUS01`（InfluxDB 以 `station_code` 作为 tag） |
| 业务库资源接口（`/stations`、`/devices`、`/alerts`、`/qc-tasks`、`/reports`、`/history`、`/stats`、`/export`） | `stationId` | Long | 站点主键 ID |
| 多站点批量入参（如 `/realtime/compare`） | `stationIds` | String | 站点 ID 逗号分隔，如 `1,2` |

> 时序库以站点编码为 tag，直接按编码查询可省去一次业务库查档；业务库资源以主键关联，故按 ID 传参。新增接口须遵循本约定，不得混用。

### 1.7 权限模型与权限列口径

接口鉴权以**权限点**（`permission.perm_code`）为准，角色只是权限点的集合，
授予关系见 `V1__init.sql` 的 `role_permission` 种子数据（ADMIN 持有全部权限）。

下表各接口的「权限」列取值含义：

- `公开`：无需认证。对应 `SecurityConfig` 的 `WHITELIST`（无条件放行）或 `PUBLIC_GET`（仅放行 GET）
- `登录`：任意已认证用户，无权限点要求（`isAuthenticated()`）
- `xxx:yyy`：需持有该权限点（`@PreAuthorize("hasAuthority('xxx:yyy')")`）

| 权限点 | 说明 | 持有角色 |
|---|---|---|
| station:view / station:edit | 站点查看 / 站点维护 | 全部角色 / ADMIN |
| device:view / device:edit | 设备查看 / 设备维护 | FORECASTER、OPERATOR、ADMIN / OPERATOR、ADMIN |
| data:query / data:export | 数据查询 / 数据导出 | 全部角色 / FORECASTER、ADMIN |
| forecast:view / forecast:order | 预报查看 / 预报订正 | USER、FORECASTER、ADMIN / FORECASTER、ADMIN |
| alert:view / alert:manage | 告警查看 / 告警规则维护 | USER、FORECASTER、ADMIN / FORECASTER、ADMIN |
| qc:review | 质控审核 | FORECASTER、OPERATOR、ADMIN |
| article:manage | 服务内容管理 | ADMIN |
| report:generate | 报表生成 | FORECASTER、ADMIN |
| user:manage / sys:manage | 用户管理 / 系统管理 | ADMIN |

> 注意：`USER` 角色不含 `data:export` 与 `device:view`，`OPERATOR` 不含 `alert:view`——
> 前端页面路由守卫按角色放行，接口最终以权限点判定，两者需保持一致。

---

## 2. 接口清单（按模块）

### 2.1 认证与用户（/auth, /users）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | /auth/login | 登录，返回 accessToken/refreshToken | 公开 |
| POST | /auth/refresh | 刷新 Token | 公开（凭 refreshToken） |
| POST | /auth/logout | 登出（Token 加入黑名单） | 登录 |
| GET | /auth/profile | 当前用户信息+权限列表 | 登录 |
| GET | /users | 用户分页列表（keyword 过滤） | user:manage |
| POST | /users | 新增用户 | user:manage |
| PUT | /users/{id} | 修改用户（含角色分配） | user:manage |
| PATCH | /users/{id}/status | 启用/禁用 | user:manage |

### 2.2 站点与设备（/stations, /devices）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /stations | 站点分页列表（含在线状态、最新数据摘要） | station:view |
| GET | /stations/{id} | 站点详情（档案+设备清单+最新观测） | station:view |
| POST / PUT / DELETE | /stations | 站点档案维护 | station:edit |
| GET | /stations/map | 地图站点聚合（坐标+状态+告警角标） | 公开 |
| GET | /devices?stationId= | 设备列表 | device:view |
| POST / PUT / DELETE | /devices | 设备维护 | device:edit |
| GET /maintenance-records?stationId=&deviceId= | 运维记录分页 | device:view |
| POST /maintenance-records | 新增运维记录 | device:edit |

### 2.3 实时监测（/realtime）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /realtime/latest?stationCode= | 站点最新观测（优先读实时缓存，缺失时降级查时序库；两条路径均为质控后数据，统一返回 source/ts/elements/qcFlag） | 公开 |
| GET | /realtime/curve?stationCode=&hours=24&granularity=auto | 最近 N 小时要素曲线（hours 上限 168；granularity 取 raw/5m/15m/1h/1d/auto） | 公开 |
| GET | /realtime/compare?stationIds=&element=&startTime=&endTime=&granularity=auto | 多站点同要素对比（时间轴取并集对齐，缺测为 null；单次最多 6 站，跨度上限 7 天） | 登录 |
| WS | /ws/realtime | 实时数据推送，仅推**质控通过**的数据（与 `/realtime/curve` 同口径）。订阅：`{"action":"subscribe","stationCode":"CAMPUS01"}`（`"*"` 订阅全部）；推送：`{"type":"latest","stationCode":"CAMPUS01","ts":"…","qcFlag":"passed","elements":{…}}` | 公开 |
| WS | /ws/alert | 告警事件全量广播：`{"type":"alert","stationCode":…,"level":…,"content":…,"alertTime":…}` | 登录 |

> WebSocket 鉴权：浏览器 WebSocket API 无法自定义请求头，需登录的通道通过握手地址携带 Token——`ws://<host>/api/v1/ws/alert?token=<accessToken>`。查询参数通道仅对 `/ws/**` 开放，普通 REST 接口仍只认 `Authorization` 头。

> `/realtime/latest/batch`（多站点最新数据）为早期设计条目，当前未实现，故未列入上表；批量取数请使用 `/realtime/compare`。

### 2.4 历史数据查询与统计（/history, /stats）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /history?stationId=&elements=&startTime=&endTime=&granularity=min/hour/day&qcFlag= | 历史时序数据（InfluxDB） | data:query |
| GET | /stats/daily?stationId=&date= | 日统计（均值/极值及出现时间） | data:query |
| GET | /stats/monthly?stationId=&month= | 月统计 | data:query |
| GET | /stats/yearly?stationId=&year= | 年统计 | data:query |
| GET | /stats/extreme?stationId=&element=&startTime=&endTime= | 极值统计 | data:query |
| GET | /stats/climate?stationId=&month=&element= | 气候平均值对比（同期多年） | data:query |
| GET | /export?stationId=&elements=&startTime=&endTime=&format=excel/csv/txt | 异步导出，返回任务 ID（excel 生成 xlsx） | data:export |
| GET | /export/tasks/{taskId} | 查询导出任务状态与下载地址 | data:export |
| GET | /export/tasks/{taskId}/download | 下载导出文件（任务完成后方可下载） | data:export |

### 2.5 预报（/forecasts）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /forecasts?stationCode=&model=stat&range=72 | 0–72h 逐小时预报产品（model 取 stat/ml/manual，range 上限 72） | 公开 |
| GET | /forecasts/model-compare?stationCode=&element=temp&range=72 | 多模型预报对比曲线 | forecast:view |
| GET | /forecasts/verification?stationCode=&model=stat&days=7 | 预报检验评分（MAE/RMSE/TS，days 上限 30） | forecast:view |
| POST | /forecasts/backtest?stationCode=&days=3 | 预报回算：重演历史预报以产出检验样本（省略 stationCode 则全部启用站点，days 上限 7） | forecast:view |
| POST | /forecasts/generate?stationCode= | 手动触发预报 Agent（省略 stationCode 则全部启用站点） | forecast:view |
| POST | /forecasts/{id}/revisions | 预报订正（写 forecast_order） | forecast:order |

### 2.6 告警（/alert-rules, /alerts）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /alert-rules?stationId=&type=&status= | 规则分页 | alert:view |
| POST / PUT / DELETE | /alert-rules | 规则维护（阈值、等级、渠道） | alert:manage |
| PATCH | /alert-rules/{id}/status | 启用/停用 | alert:manage |
| GET | /alerts?stationId=&level=&type=&startTime=&endTime=&status= | 告警历史分页 | alert:view |
| GET | /alerts/stat?startTime=&endTime= | 告警统计（按类型/等级/站点） | alert:view |
| PATCH | /alerts/{id}/relieve | 手动解除告警 | alert:manage |
| GET | /alert-subscribes | 我的告警订阅 | 登录 |
| POST / DELETE | /alert-subscribes | 订阅/取消订阅（站点+类型+渠道） | 登录 |

> 告警记录含两个文案字段：`content` 为规则判定的事实描述（站点/要素/触发值/阈值，必定有值、可机读），
> `aiContent` 为大模型生成的处置建议（影响对象 + 可执行动作），大模型不可用时为 `null`。
> 两者分列存放，便于分辨哪句来自规则、哪句来自模型。

### 2.7 质控审核（/qc-tasks）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /qc-tasks?stationId=&status=&startTime=&endTime= | 审核任务分页 | qc:review |
| PATCH | /qc-tasks/{id}/review | 审核：`{"action":"confirm/revise/void","revisedValue":12.5}` | qc:review |
| POST | /qc-tasks/interpolate | 手动触发一轮缺测插补（FR-QC-05），返回本轮回填统计 | qc:review |

### 2.8 气象服务与报表（/articles, /reports）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /articles?category=&page= | 服务内容分页（已发布） | 公开 |
| GET | /articles/{id} | 详情 | 公开 |
| POST / PUT / PATCH /articles | 管理端：新增/修改/上下架 | article:manage |
| GET | /reports?stationId=&type=&period= | 报表文件分页 | 登录 |
| POST | /reports/generate | 手动生成报表 | report:generate |
| GET | /reports/{id}/download | 下载报表文件 | 登录 |

### 2.9 系统管理与角色（/sys, /roles）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /sys/configs | 系统参数查询（分页+按 key 搜索） | sys:manage |
| PUT | /sys/configs/{id} | 修改系统参数 | sys:manage |
| GET | /sys/logs/operations?userId=&module=&startTime=&endTime= | 操作日志分页 | sys:manage |
| GET | /roles | 角色列表（含权限编码） | sys:manage |
| GET | /roles/permissions | 权限清单（供角色权限配置选择） | sys:manage |
| POST / PUT | /roles | 角色新增 / 权限全量重设 | sys:manage |

### 2.10 智能助手（/assistant）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | /assistant/chat | 气象决策助手对话：`{"question":"昨天哪个站点风速最大？"}`，由大模型规划并调用气象数据工具后作答；大模型不可用时返回 `source=unavailable` 的提示而非报错 | 登录 |

> 大模型经 OpenAI 兼容协议接入（默认 DeepSeek，见 `spring.ai.openai.*`，密钥走环境变量 `LLM_API_KEY`）。
> 返回结构：`{answer, source: llm|unavailable, model, tools[]}`，其中 `tools` 为本轮实际调用的工具留痕
> （`{name, arguments, result}`，按调用顺序），供前端展示「AI 查了什么、拿到了什么」；大模型不可用时为空数组。
> 模型不直接访问数据库，只读数据一律经 `agent/assistant/MeteoTools` 暴露的工具获取（工具内部委托既有 Service，
> 不新增数据访问逻辑）：

| 工具 | 能力 | 底层服务 |
|---|---|---|
| `listStations` | 站点编码、名称、在线状态、当前最高告警等级 | StationService |
| `queryRealtime` | 站点最新质控后观测（含质控标记） | RealtimeService |
| `queryHistory` | 历史观测统计摘要（样本数/均值/极值/最新），支持中文要素名，跨度上限 7 天 | DataQueryService |
| `queryAlerts` | 最近告警记录（等级/站点/触发值/状态），回溯上限 168 小时 | AlertService |
| `queryForecast` | 未来 1–72 小时多模型预报曲线的时次范围与极值 | ForecastService |
| `getStationInfo` | 站点档案详情（区划/经纬度/海拔/类型/状态） | StationService |
| `searchKnowledge` | 知识库检索：气象服务已发布文章（预警信号发布标准、防灾避险指引、农业与出行建议），回答须注明来源标题 | KnowledgeBaseService + 向量库 |

> 工具均为**只读**；工具内部异常与「无数据」都转为明确的中文提示返回给模型，
> 避免模型因拿不到数据而改用常识值编造气象数值。

> **知识库（RAG）**：语料为「气象服务」中 `publish_status=1` 的文章，切块后向量化存入进程内向量库
> （Spring AI `SimpleVectorStore`，检索侧只依赖 `VectorStore` 接口，可平滑替换为 Redis/Milvus）。
> 索引在应用启动后构建，内容新增/修改/上下架时由事件触发异步重建。
> **向量化与对话不是同一家厂商**：DeepSeek 只提供 chat 接口，没有 embeddings（实测 `/v1/embeddings` 返回 404），
> 故 embedding 单独指向阿里云百炼（通义千问）的 OpenAI 兼容端点，配置项为
> `spring.ai.openai.embedding.base-url/api-key/options.model`（覆盖 chat 的同名配置，两者互不影响），
> 密钥走环境变量 `EMBEDDING_API_KEY`。两个易踩的坑：
> ① base-url 填 `https://dashscope.aliyuncs.com/compatible-mode`，**不要带 `/v1`**——
> Spring AI 会自动追加 `embeddings-path`（默认 `/v1/embeddings`），带上会拼成 `…/v1/v1/embeddings` 而 404；
> ② model 必须是带版本号的 `text-embedding-v4`，百炼的文本向量模型不带版本号调不通。
> 未配置密钥或向量化失败时知识库标记为未就绪，检索工具如实回答「知识库不可用 / 未检索到依据」，
> 不会让模型改用自己的常识作答。

---

## 3. 典型接口示例

### 3.1 登录 `POST /api/v1/auth/login`

请求：

```json
{ "username": "admin", "password": "Passw0rd!" }
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOi...",
    "refreshToken": "d8f3a9...",
    "expiresIn": 7200,
    "user": { "id": 1, "username": "admin", "realName": "管理员", "roles": ["ADMIN"] }
  },
  "timestamp": 1760000000000
}
```

### 3.2 历史查询 `GET /api/v1/history?stationId=1&elements=temp,wind_speed&startTime=2026-09-01 00:00:00&endTime=2026-09-15 00:00:00&granularity=hour`

响应（data）：

```json
{
  "stationId": 1,
  "granularity": "hour",
  "series": {
    "temp": [
      { "time": "2026-09-01 00:00:00", "value": 24.6, "qcFlag": "passed" },
      { "time": "2026-09-01 01:00:00", "value": 24.1, "qcFlag": "interpolated" }
    ],
    "wind_speed": [
      { "time": "2026-09-01 00:00:00", "value": 3.2, "qcFlag": "passed" }
    ]
  }
}
```

### 3.3 新增告警规则 `POST /api/v1/alert-rules`

请求：

```json
{
  "stationId": 1,
  "alertType": 1,
  "element": "rain",
  "condition": 3,
  "threshold": 16.0,
  "durationMin": 60,
  "level": 2,
  "upgradeLevel": 3,
  "channels": ["web", "sms"]
}
```

### 3.4 错误响应示例

```json
{
  "code": 60002,
  "message": "告警规则阈值非法：小时雨量阈值须在 0~200mm 之间",
  "data": null,
  "timestamp": 1760000000000
}
```

---

## 4. 接口设计规约（开发必读）

1. **幂等性**：POST 导出/报表生成等耗时操作提供 `clientToken` 防重复提交。
2. **参数校验**：所有入参使用 `@Validated` + JSR-303 注解，错误统一返回 40001 并附字段级明细。
3. **数据权限**：接口层校验用户对站点的可见范围，防止横向越权。
4. **限流**：导出、手动生成预报等重操作经 Redis 令牌桶限流（默认 5 次/分钟/用户）。
5. **文档**：所有接口添加 `@Operation`/`@Schema` 注解，springdoc 生成 `/v3/api-docs` 与 Swagger UI。
6. **版本**：破坏性变更升 `/api/v2`，`/api/v1` 保持兼容。
