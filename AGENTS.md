# AGENTS.md — 项目目录结构与编码规范

> 本文档供团队成员与 AI 编码助手阅读，是本仓库的协作契约。任何代码提交须符合本文规范。
> 关联文档：[SRS](docs/01-SRS-需求规格说明书.md) · [架构设计](docs/02-系统架构设计.md) · [数据库设计](docs/03-数据库ER模型设计.md) · [API 规范](docs/04-RESTful-API接口规范.md)

---

## 1. 技术栈基线

| 项 | 选型 |
|---|---|
| 语言/JDK | Java 17 |
| 后端框架 | Spring Boot 3.2+ / Spring Security / MyBatis Plus 3.5 |
| AI | Spring AI 1.0 / LangChain4j 1.0 |
| 中间件 | RabbitMQ 3.12 / Redis 7 / EMQX 5 / Nginx 1.24 |
| 数据 | MySQL 8 / InfluxDB 2.7 |
| 前端 | Vue 3.4+ / Vite / Pinia / ECharts 5 / 高德地图 / DataV |
| 构建 | Maven 3.9+ / Node 20 LTS |

---

## 2. 仓库与项目目录结构

仓库采用前后端单仓（monorepo）：

```
software/
├── AGENTS.md                    # 本文档
├── docs/                        # 设计文档
│   ├── 01-SRS-需求规格说明书.md
│   ├── 02-系统架构设计.md
│   ├── 03-数据库ER模型设计.md
│   └── 04-RESTful-API接口规范.md
├── backend/                     # Spring Boot 后端（Maven 单模块，按包分层）
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/campus/meteo/
│       │   ├── MeteoApplication.java
│       │   ├── config/             # Spring/Security/Swagger/MQTT/Influx 配置类
│       │   ├── common/             # 通用层
│       │   │   ├── result/         # Result、PageResult、错误码 ErrorCode
│       │   │   ├── exception/      # BizException、GlobalExceptionHandler
│       │   │   ├── constant/       # 常量、枚举（AlertLevel、QcFlag…）
│       │   │   └── util/           # 工具类
│       │   ├── controller/         # REST 控制器（按模块子包：station/alert/…）
│       │   ├── service/            # 业务接口 + impl 子包
│       │   ├── mapper/             # MyBatis Plus Mapper
│       │   ├── entity/             # 数据库实体（@TableName）
│       │   ├── dto/                # 请求/响应 DTO（XxxQuery/XxxSaveReq/XxxResp）
│       │   ├── agent/              # Agent 智能体层
│       │   │   ├── collector/      # 采集 Agent
│       │   │   ├── qc/             # 质控 Agent
│       │   │   ├── forecast/       # 预报 Agent（含模型适配）
│       │   │   ├── alert/          # 告警 Agent
│       │   │   └── report/         # 报表 Agent
│       │   ├── mq/                 # RabbitMQ 生产/消费者、Topic 常量
│       │   ├── influx/             # InfluxDB 读写封装
│       │   └── security/           # JWT 过滤器、UserDetailsService、RBAC
│       └── main/resources/
│           ├── application.yml         # 公共配置
│           ├── application-dev.yml     # 环境配置（密码等走环境变量）
│           ├── mapper/                 # XML（复杂 SQL 才建）
│           └── db/migration/           # Flyway 建表脚本 V1__init.sql …
├── frontend/                    # Vue3 前端
│   ├── package.json
│   └── src/
│       ├── api/                  # 按模块封装的 axios 调用
│       ├── views/                # 页面（dashboard/map/history/forecast/alert/admin）
│       ├── components/           # 通用组件（仪表盘卡片、图表封装）
│       ├── stores/               # Pinia
│       ├── router/
│       ├── utils/                # 请求拦截、格式化
│       └── assets/
└── deploy/                      # 部署脚本与配置（nginx、docker-compose、emqx）
```

> 分层依赖只能自上而下：controller → service → mapper/agent → 数据层。禁止 controller 直接调 mapper、service 反向依赖 controller。

---

## 3. 后端编码规范

### 3.1 命名

| 类型 | 规则 | 示例 |
|---|---|---|
| 类 | 大驼峰，后缀表意 | `StationController` `AlertRuleServiceImpl` `QcAgent` |
| 方法/变量 | 小驼峰，布尔用 is/has 前缀 | `getLatestObs()` `isOnline` |
| 常量/枚举 | 全大写下划线 | `MQ_TOPIC_METEO_RAW` |
| 数据库 | snake_case，表名单数 | `alert_record` `alert_record_id` |
| API 路径 | 复数 kebab-case | `/alert-rules` `/qc-tasks` |
| 包 | 全小写单词 | `com.campus.meteo.qc` |

### 3.2 分层与职责

- **Controller**：只做参数校验 + 调 service + 组装 Result，禁止写业务逻辑。
- **Service**：业务逻辑、事务边界（`@Transactional` 只加在 service 方法）。
- **Mapper**：单表用 MyBatis Plus，复杂查询写 XML；禁止 `select *`。
- **DTO**：对外交互一律 DTO，禁止 entity 直接出参；入参用 `XxxSaveReq`/`XxxQuery` + JSR-303 注解。
- **Agent**：每个 Agent 一个独立包；Agent 之间只通过 MQ Topic 通信，禁止直接方法调用。

### 3.3 统一返回与异常

- 所有接口返回 `Result<T>`（见 API 规范 1.2），错误码只使用 `ErrorCode` 枚举中定义的值。
- 业务异常抛 `BizException(ErrorCode, message)`，由 `GlobalExceptionHandler` 统一捕获；禁止在 controller 手写 try-catch 返回。

### 3.4 数据库规范

- 建表改动一律通过 Flyway 迁移脚本（`V{n}__desc.sql`），禁止直接改库。
- 所有表含 `id/create_time/update_time/deleted`；MyBatis Plus 开启逻辑删除与自动填充。
- 大字段（TEXT）单独查询，避免拖累列表接口。

### 3.5 时序数据（InfluxDB）规范

- 写入统一走 `influx/` 包封装的 `ObsWriter`/`FcstWriter`，禁止散落各处。
- 查询必须限定 `station_code` tag 与时间范围，禁止无界查询。
- 质控标记 `qc_flag` 遵循：raw/passed/suspect/interpolated/revised。

### 3.6 日志规范

- 使用 `@Slf4j`；日志占位符 `{}`，禁止字符串拼接。
- 关键链路（采集→质控→告警）日志必须携带 `msgId`（取自 MQTT 消息 ID），便于全链路追踪。
- 日志级别：ERROR 系统故障（含堆栈）、WARN 可疑但已处理、INFO 关键业务动作、DEBUG 开发细节（生产关闭）。
- 禁止打印密码、Token、手机号明文（脱敏后再打）。

### 3.7 事务与消息

- DB 事务与 MQ 发送分离：先落库，再发消息；消费端幂等（以 `msgId` + Redis 去重）。
- MQ 消费者手动 ack，异常消息重试 3 次后进入死信队列并告警。

---

## 4. 前端编码规范

- 组件名大驼峰多单词（`StationMapCard.vue`）；组合式 API（`<script setup>`）。
- API 调用统一在 `src/api/` 封装，页面禁止直接 `axios`。
- 图表统一封装在 `components/charts/`，基于 ECharts 按需引入。
- 时间展示统一走 `utils/format.ts`；单位（℃、m/s、mm）在展示层拼接。
- 路由守卫按角色控制访问；未授权页面重定向 403。

---

## 5. Git 规范

### 5.1 分支模型

```
main        # 稳定可发布，只接受 MR 合入
dev         # 开发主线
feature/xxx # 功能分支，如 feature/alert-agent
fix/xxx     # 缺陷修复
```

### 5.2 提交信息（Conventional Commits）

```
<type>(<scope>): <subject>

type: feat|fix|refactor|docs|test|chore
scope: 模块名，如 station、qc-agent、api、frontend
```

示例：`feat(alert-agent): 实现暴雨持续阈值升级判定`

### 5.3 其他

- 提交前须通过本地编译与单测：`mvn verify`。
- 禁止提交 `application-local.yml`、node_modules、IDE 配置（`.gitignore` 已覆盖）。
- 合入 dev 须经 Code Review（至少 1 人 approve）。

---

## 6. 测试规范

- Service 层核心逻辑（质控判定、告警升级、插补算法）必须有单元测试（JUnit 5 + Mockito），覆盖率 ≥ 60%。
- Controller 层接口用 `@WebMvcTest` + MockMvc 冒烟验证。
- 集成测试用 Testcontainers（MySQL/RabbitMQ/InfluxDB）。

---

## 7. 给 AI 助手的特别约定

1. 修改代码前先阅读本文件与 `docs/` 对应设计文档，实现须与文档一致；发现文档与代码冲突时，先提出再修改。
2. 新增接口必须同时更新 Swagger 注解；改动表结构必须新增 Flyway 脚本，禁止修改已发布的迁移脚本。
3. 遵守分层依赖方向与命名规范；不要引入设计文档之外的新依赖（新增依赖须说明理由并征得同意）。
4. 所有回复、代码注释、提交信息说明使用中文；标识符使用英文。
5. 敏感信息（数据库密码、API Key）一律走环境变量，示例值写 `changeme`。
