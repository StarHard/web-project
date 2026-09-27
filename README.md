# 校园智能气象服务系统

面向高校校园与农业试验站的**嵌入式气象观测 + AI 决策服务**平台。系统覆盖从设备数据接入到决策建议的完整链路：
MQTT 采集 → 质控与插补 → 时序存储 → 预报与告警 → **大模型决策助手**。

> 参赛作品：**2026 iCAN 大学生创新创业大赛 · AI 应用创新挑战赛（软件赛道）**
> 本项目为前后端单仓（monorepo），后端 Spring Boot 3.5 + 前端 Vue 3。

---

## 一、核心能力

### 1. 气象数据全链路

| 环节 | 能力 |
|---|---|
| 采集 | MQTT 订阅 + 定时兜底；协议解析、单位归一、站点识别；断线重连与订阅失效自愈（链路体检） |
| 质控 | 极值 / 时间一致性 / 空间一致性三类检验；缺测智能插补（线性插值 + 邻近站点回归）；可疑数据人工审核队列 |
| 存储 | MySQL 存业务数据，InfluxDB 存观测与预报时序；写入统一封装，查询强制限定站点与时间范围 |
| 预报 | 统计降尺度基线模型，0–72h 逐小时预报；多模型对比；与实况对比的 MAE/RMSE 准确率检验；预报员人工订正留痕 |
| 告警 | 多要素多阈值规则、蓝/黄/橙/红四级、持续超限自动升级、抑制窗口去重、条件恢复自动解除、WebSocket 实时推送 |
| 数据服务 | 多条件查询、日/月/年统计、极值与气候均值对比、Excel/CSV/TXT 导出 |

### 2. AI 决策内核（本项目的技术亮点）

系统原设计中的五个「Agent」是 MQ 流水线上的数据处理角色，**并不具备大模型能力**。本项目在此之上补齐了真正的 AI 内核：

| 能力 | 说明 |
|---|---|
| **决策智能体（工具调用）** | 大模型自主规划并调用 7 个只读数据工具（站点清单 / 实时观测 / 历史统计 / 多模型预报 / 告警记录 / 站点档案 / 知识库检索），全部委托既有 Service，不新增数据访问逻辑 |
| **调用过程可视化** | 每次问答把实际调用的工具、参数、返回内容回传前端展示——让「回答基于真实数据」这件事**可被验证**，而非仅凭信任 |
| **AI 预警文案** | 告警落库时同步生成面向值班人员的处置建议（影响对象 + 可执行动作），与规则判定的事实描述**分列存放**，可分辨哪句来自规则、哪句来自模型 |
| **RAG 知识库** | 以「气象服务」已发布文章为语料做检索增强，回答预警信号标准、防灾避险规范类问题时**注明来源文章标题** |

AI 部分的工程底线（贯穿上述四项）：

- **禁止幻觉**：只依据工具与知识库返回的内容作答，严禁编造数值；数据缺失时明确回答「无数据」
- **不越权**：工具全部只读，写操作不开放给模型
- **可降级**：大模型或向量服务不可用时，助手返回明确提示、预警回退模板文案、知识库如实报告「未检索到依据」，**不影响系统其它功能**

---

## 二、技术栈

| 项 | 选型 |
|---|---|
| 语言 / JDK | Java 17 |
| 后端 | Spring Boot 3.5 / Spring Security / MyBatis Plus 3.5 / springdoc-openapi |
| AI | Spring AI 1.1.8；对话走 OpenAI 兼容协议接入 **DeepSeek**，向量化接入**阿里云百炼 text-embedding-v4**；向量存储用进程内 `SimpleVectorStore` |
| 中间件 | RabbitMQ 3.12 / Redis 7 / Mosquitto（开发）/ EMQX 5（生产） |
| 数据 | MySQL 8 / InfluxDB 2.7 |
| 前端 | Vue 3.5 / Vite 5 / Pinia / ECharts 5 / 高德地图 / DataV |
| 构建 | Maven 3.9+（含 Maven Wrapper）/ Node 20 LTS |

---

## 三、快速开始（Windows）

> **平台覆盖**：下面的脚本是 PowerShell，**已在 Windows 11 上完整实测**。Linux / macOS 没有已验证的上手路径——
> 中间件可改用 [deploy/docker-compose.yml](deploy/docker-compose.yml)（该方案自身标注为「未实测验证」，
> 见 [deploy/README.md](deploy/README.md)），后端与前端仍需按第 1、2 步单独启动。

### 前置条件

- **JDK 17+**（脚本会自动探测 `JAVA_HOME`）
- **Node 20 LTS**（仅前端需要）
- **MySQL 8**（需自行安装，脚本只负责建库 `meteo`，建表由 Flyway 在启动时自动执行）

### 第 1 步：启动中间件与后端

```powershell
# 首次运行会下载并解压全部中间件到 tools/（约 270MB，需数分钟）
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1

# 指定 MySQL 密码（默认 123456）
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -MysqlPassword 你的密码
```

脚本依次完成：准备中间件 → 启动 Redis / InfluxDB / Mosquitto / RabbitMQ → 初始化 InfluxDB → 建 `meteo` 库 → 启动后端。
日志在 `tools/app.log`。之后重启可加 `-SkipSetup` 跳过下载步骤。

演示用的历史观测（7 天逐小时）由后端启动时**按与实时链路相同的物理模型自动补齐**，
按整点只补缺失的时次、已有的不重写，无需额外步骤；停机造成的空洞也会在下次重启时补上。
清空时序库（`scripts\clear-timeseries.ps1`）后重启即可重建。

### 第 2 步：启动前端

```powershell
cd frontend
npm install
npm run dev
```

**可选：配置高德地图 Key**（不配置时「站点地图」页降级为站点列表视图，其余页面不受影响）

「站点地图」依赖高德地图 JS API。在 `frontend/` 下新建 `.env.local`（该文件已被 `.gitignore` 忽略，不会入库）：

```
VITE_AMAP_KEY=你的Key
VITE_AMAP_SECURITY_KEY=你的安全密钥
```

两项都在[高德开放平台](https://lbs.amap.com)申请：创建应用后**服务平台必须选「Web端(JS API)」**——选成「Web服务」是给 REST 接口用的，浏览器里加载会报 `INVALID_USER_KEY`；安全密钥与 Key 并列显示，**JS API 2.0 必须配套使用**，只填 Key 不填安全密钥会报 `INVALID_USER_SCODE`、地图加载不出来。改完要重启 `npm run dev`，Vite 只在启动时读环境变量。

### 第 3 步：访问

| 入口 | 地址 |
|---|---|
| 前端界面 | http://localhost:5173 |
| 接口文档（Swagger） | http://localhost:8080/api/v1/swagger-ui.html |
| InfluxDB 控制台 | http://localhost:8086 |

**演示账号：`admin` / `Admin@123`**（由 Flyway 迁移脚本初始化，首次登录后请修改）

### 停止环境

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
```

> 详细部署说明（含 Docker Compose 方案、端口清单、环境变量、Mosquitto 服务残留处理）见 **[deploy/README.md](deploy/README.md)**。

---

## 四、AI 服务配置

AI 能力是**可选增强**：不配置密钥时系统仍可正常启动与使用，仅助手降级提示、预警回退模板文案、知识库报告不可用。

在 `tools/` 下创建密钥文件（该目录已被 `.gitignore` 忽略，不会入库），`start-all.ps1` 会自动载入：

| 文件 | 环境变量 | 用途 | 是否必需 |
|---|---|---|---|
| `tools/llm-key.txt` | `LLM_API_KEY` | 对话大模型（默认 DeepSeek） | 使用助手/预警文案时需要 |
| `tools/embedding-key.txt` | `EMBEDDING_API_KEY` | 知识库向量化（默认阿里云百炼） | 使用知识库检索时需要 |

也可直接设置同名环境变量。换厂商只需改环境变量，无需改代码：

```
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
EMBEDDING_MODEL=text-embedding-v4
```

> **注意**：`EMBEDDING_BASE_URL` 不要带 `/v1`——Spring AI 会自动追加以 `/v1/embeddings` 结尾的路径。
> 详细配置与踩坑说明见 [docs/04-RESTful-API接口规范.md](docs/04-RESTful-API接口规范.md) 智能助手章节。

---

## 五、目录结构

```
software/
├── AGENTS.md          # 协作契约：目录结构、编码规范、Git 规范（务必先读）
├── docs/              # 设计文档（SRS / 架构 / 数据库 / API）
├── backend/           # Spring Boot 后端（按包分层）
│   └── src/main/java/com/campus/meteo/
│       ├── agent/     # 智能体层：采集 / 质控 / 预报 / 告警 / 报表 / 助手
│       ├── controller/ service/ mapper/ entity/ dto/
│       ├── influx/    # 时序库读写封装
│       ├── mq/        # RabbitMQ 拓扑与 Topic 契约
│       └── security/  # JWT 与 RBAC
├── frontend/          # Vue3 前端
│   └── src/{api,views,components,stores,router,utils}
├── scripts/           # 便携式环境脚本（下载初始化中间件 / 启动 / 停止 / 重置时序库）
└── deploy/            # Docker Compose 与部署说明
```

分层依赖只能自上而下：`controller → service → mapper/agent → 数据层`。

---

## 六、功能界面

| 分组 | 页面 |
|---|---|
| 智能决策 | 智能助手（对话 + 工具调用可视化 + 知识库引用） |
| 监测中心 | 实时监测、多站对比、大屏看板、站点地图、历史数据 |
| 预报预警 | 精细预报、灾害告警、质控审核 |
| 服务与报表 | 气象服务、统计报表 |
| 系统管理 | 站点设备、告警规则、用户角色、系统管理 |

---

## 七、测试与构建

```powershell
# 后端：运行全部单元测试（含 Maven Wrapper，无需预装 Maven）
cd backend
$env:JAVA_HOME='你的JDK路径'; .\mvnw.cmd -B test

# 前端：类型检查 + 生产构建
cd frontend
npm run type-check
npm run build
```

当前测试规模：**148 个单元测试用例全部通过**（16 个测试类），覆盖质控判定、插补算法、告警升级、气象数据模拟、预报回算与检验、多站对比、AI 工具集与知识库等核心逻辑。

---

## 八、已知边界

以下为**有意未实现**的部分（按设计文档的优先级取舍），非缺陷：

| 项 | 说明 |
|---|---|
| GRIB 数值产品接入 | 预报采用统计降尺度基线模型，未接入外部数值模式产品 |
| 短信 / 邮件 / 微信网关 | 告警渠道中 Web 站内信已打通，其余渠道**落库待发**，未对接服务商 |
| 农业 / 旅游气象服务内容 | [SRS](docs/01-SRS-需求规格说明书.md) 中 FR-WS-01/02 未实现（P1，本次范围外） |
| 数据共享开放 API | FR-DS-05（P2，本次范围外） |
| 硬件依赖 | 无实体气象站时，由 `DataSimulator` 生成模拟观测数据用于演示（开发环境默认开启） |

---

## 九、文档索引

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 目录结构、编码规范、Git 规范、测试规范 |
| [docs/01-SRS-需求规格说明书.md](docs/01-SRS-需求规格说明书.md) | 需求条目与优先级 |
| [docs/02-系统架构设计.md](docs/02-系统架构设计.md) | 架构分层、Agent 设计、关键实现口径 |
| [docs/03-数据库ER模型设计.md](docs/03-数据库ER模型设计.md) | 表结构与 ER 模型 |
| [docs/04-RESTful-API接口规范.md](docs/04-RESTful-API接口规范.md) | 接口清单、权限模型、AI 工具与知识库说明 |
| [deploy/README.md](deploy/README.md) | 部署方案详解 |