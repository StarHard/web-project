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

---

## 2. 接口清单（按模块）

### 2.1 认证与用户（/auth, /users）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | /auth/login | 登录，返回 accessToken/refreshToken | 公开 |
| POST | /auth/refresh | 刷新 Token | 携带有效 refreshToken |
| POST | /auth/logout | 登出（Token 加入黑名单） | 登录 |
| GET | /auth/profile | 当前用户信息+权限列表 | 登录 |
| GET | /users | 用户分页列表（keyword 过滤） | ADMIN |
| POST | /users | 新增用户 | ADMIN |
| PUT | /users/{id} | 修改用户（含角色分配） | ADMIN |
| PATCH | /users/{id}/status | 启用/禁用 | ADMIN |

### 2.2 站点与设备（/stations, /devices）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /stations | 站点列表（含在线状态、最新数据摘要） | 登录（游客可见简化版） |
| GET | /stations/{id} | 站点详情（档案+设备清单） | 登录 |
| POST / PUT / DELETE | /stations | 站点档案维护 | ADMIN |
| GET | /stations/map | 地图站点聚合（坐标+状态+告警角标） | 公开 |
| GET | /devices?stationId= | 设备列表 | 登录 |
| POST / PUT / DELETE | /devices | 设备维护 | ADMIN/OPERATOR |
| GET /maintenance-records?stationId=&deviceId= | 运维记录分页 | 登录 |
| POST /maintenance-records | 新增运维记录 | OPERATOR |

### 2.3 实时监测（/realtime）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /realtime/latest?stationId= | 站点最新一条全要素数据（Redis 缓存） | 公开 |
| GET | /realtime/latest/batch | 多站点最新数据（`stationIds=a,b,c`） | 公开 |
| GET | /realtime/curve?stationId=&element=&hours=24 | 最近 N 小时要素曲线 | 公开 |
| GET | /realtime/compare?stationIds=&element=&startTime=&endTime= | 多站点同要素对比 | 登录 |
| WS | /ws/realtime | WebSocket 订阅推送：`{"type":"latest","stationId":1,...}` | 登录 |
| WS | /ws/alert | WebSocket 告警推送 | 登录 |

### 2.4 历史数据查询与统计（/history, /stats）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /history?stationId=&elements=&startTime=&endTime=&granularity=min/hour/day&qcFlag= | 历史时序数据（InfluxDB） | 登录 |
| GET | /stats/daily?stationId=&date= | 日统计（均值/极值及出现时间） | 登录 |
| GET | /stats/monthly?stationId=&month= | 月统计 | 登录 |
| GET | /stats/yearly?stationId=&year= | 年统计 | 登录 |
| GET | /stats/extreme?stationId=&element=&startTime=&endTime= | 极值统计 | 登录 |
| GET | /stats/climate?stationId=&month=&element= | 气候平均值对比（同期多年） | 登录 |
| GET | /export?stationId=&elements=&startTime=&endTime=&format=excel/csv/txt | 异步导出，返回任务 ID（excel 生成 xlsx） | USER+ |
| GET | /export/tasks/{taskId} | 查询导出任务状态与下载地址 | USER+ |
| GET | /export/tasks/{taskId}/download | 下载导出文件（任务完成后方可下载） | USER+ |

### 2.5 预报（/forecasts）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /forecasts?stationId=&range=72h | 0–72h 逐小时预报产品 | 公开 |
| GET | /forecasts/model-compare?stationId=&element= | 多模型预报对比曲线 | FORECASTER |
| GET | /forecasts/verification?stationId=&startTime=&endTime= | 预报检验评分（MAE/RMSE/TS） | FORECASTER |
| POST | /forecasts/{id}/revisions | 预报订正（写 forecast_order） | FORECASTER |
| POST | /forecasts/generate?stationId= | 手动触发预报 Agent | FORECASTER |

### 2.6 告警（/alert-rules, /alerts）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /alert-rules?stationId=&type=&status= | 规则分页 | 登录 |
| POST / PUT / DELETE | /alert-rules | 规则维护（阈值、等级、渠道） | FORECASTER/ADMIN |
| PATCH | /alert-rules/{id}/status | 启用/停用 | FORECASTER/ADMIN |
| GET | /alerts?stationId=&level=&type=&startTime=&endTime=&status= | 告警历史分页 | 登录 |
| GET | /alerts/stat?startTime=&endTime= | 告警统计（按类型/等级/站点） | 登录 |
| PATCH | /alerts/{id}/relieve | 手动解除告警 | FORECASTER |
| GET | /alert-subscribes | 我的告警订阅 | 登录 |
| POST / DELETE | /alert-subscribes | 订阅/取消订阅（站点+类型+渠道） | 登录 |

### 2.7 质控审核（/qc-tasks）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /qc-tasks?stationId=&status=&startTime=&endTime= | 审核任务分页 | OPERATOR |
| PATCH | /qc-tasks/{id}/review | 审核：`{"action":"confirm/revise/void","revisedValue":12.5}` | OPERATOR |
| POST | /qc-tasks/interpolate | 手动触发一轮缺测插补（FR-QC-05），返回本轮回填统计 | OPERATOR |

### 2.8 气象服务与报表（/articles, /reports）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET | /articles?category=&page= | 服务内容分页（已发布） | 公开 |
| GET | /articles/{id} | 详情 | 公开 |
| POST / PUT / PATCH /articles | 管理端：新增/修改/上下架 | ADMIN |
| GET | /reports?stationId=&type=&period= | 报表文件分页 | 登录 |
| POST | /reports/generate | 手动生成报表 | FORECASTER/ADMIN |
| GET | /reports/{id}/download | 下载报表文件 | 登录 |

### 2.9 系统管理（/sys）

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| GET / PUT | /sys/configs | 系统参数查询/修改（分页+按 key 搜索） | ADMIN |
| GET | /sys/logs/operations?userId=&module=&startTime=&endTime= | 操作日志分页 | ADMIN |
| GET | /roles, POST /roles, PUT /roles/{id} | 角色与权限分配 | ADMIN |

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
