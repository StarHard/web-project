# 部署环境说明

本地开发有两条路径，按机器条件选择：

| 方案 | 适用场景 | 入口 | 验证状态 |
|---|---|---|---|
| **A. 便携式脚本**（推荐无 Docker 环境） | 本机直接跑，中间件解压到 `tools/`，无需 Docker，删除目录即卸载 | `scripts/start-all.ps1` | **已实测验证**，日常开发在用 |
| **B. Docker Compose** | 有 Docker Desktop，环境与生产部署一致 | `deploy/docker-compose.yml` | **未实测验证**，详见下方「方案 B 说明」 |

> **关于验证状态**：方案 A 是本项目实际使用的路径，已长期跑通（Redis / InfluxDB / Mosquitto / RabbitMQ 四个中间件
> 由脚本管理，MySQL 需本机自备、脚本只负责建库）；方案 B 的 compose 文件已编写但
> 尚未在 Docker 环境下执行过（开发机未安装 Docker）。若你选用方案 B，请先按下方「方案 B 说明」核对凭据差异，
> 并预留中间件就绪时间，不要默认它能开箱即用。

---

## 方案 A：便携式一键启动（Windows）

```powershell
# 首次运行（自动下载并解压全部中间件，约 270MB，需数分钟）
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1

# 清理时序库并重建历史（重启后端即自动回填 7 天逐小时观测）
powershell -ExecutionPolicy Bypass -File scripts\clear-timeseries.ps1

# 指定 MySQL 账号密码
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -MysqlUser root -MysqlPassword 你的密码

# 停止全部服务
powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
```

脚本能力：

| 脚本 | 作用 |
|---|---|
| `scripts/setup-middleware.ps1` | 下载并解压 Redis / InfluxDB / Mosquitto / Erlang + RabbitMQ 到 `tools/` |
| `scripts/start-all.ps1` | 启动全部中间件 + 初始化 InfluxDB + 建库 + 启动后端（自动识别 JDK，无需预装 Maven） |
| `scripts/stop-all.ps1` | 按端口停止全部服务，保留数据 |
| `scripts/clear-timeseries.ps1` | 清空时序库（`obs_min` / `obs_hour` / `fcst`），用于重置演示数据基线 |

说明：

- **MySQL 不在脚本管理范围**：使用本机已装的 MySQL/MariaDB（XAMPP 亦可），脚本只负责创建 `meteo` 库，建表由 Flyway 在服务启动时自动完成。
- **历史观测自动回填**：后端启动时若历史为空，会用与实时链路**相同的物理模型**补齐 7 天逐小时观测（由 `meteo.simulator.backfill-days` 配置，设 0 关闭）。因此不再需要单独的灌数脚本——两套模型互不相干时，衔接处会出现明显跳变被质控判为可疑，进而冻结质控数据、刷满审核队列。
- **数据全在仓库内**：InfluxDB 数据目录被显式指定为 `tools/influxdb/data`（默认是 `%USERPROFILE%\.influxdbv2`，已避免），删除 `tools/` 即彻底清理。
- **唯一例外**：Mosquitto 官方安装包会注册一个 Windows 服务 `mosquitto`（开机自启）。`setup-middleware.ps1` 在有管理员权限时会自动移除；否则请手动执行：
  ```powershell
  sc stop mosquitto & sc delete mosquitto
  ```
- **端口占用**：8080 后端、6379 Redis、8086 InfluxDB、1883 MQTT、5672 RabbitMQ。Mosquitto 与 EMQX 协议兼容，生产环境按架构文档使用 EMQX。

---

## 方案 B：Docker Compose

> **本方案尚未实测验证**（开发机未安装 Docker，compose 文件编写后未实际执行过）。
> 选用前请先读完本节的两条注意事项。

```bash
cd deploy
docker compose up -d
```

### 注意事项一：凭据口径与方案 A 不同

方案 B 在容器内**新建**了独立账号，与方案 A 连接本机已有中间件所用的账号不一致。
照抄启动后后端若不显式覆盖环境变量，会出现「中间件都起来了但后端连不上」，
且报错点分散在 MySQL / InfluxDB / RabbitMQ 三处，不易定位：

| 项 | 方案 A（已跑通） | 方案 B（compose 默认值） | 后端需覆盖的环境变量 |
|---|---|---|---|
| MySQL | 本机已有实例，默认 `root` / `123456`（脚本参数可指定） | 容器内新建 `meteo` / `changeme` | `MYSQL_USER=meteo`、`MYSQL_PASSWORD=changeme` |
| RabbitMQ | `guest` / `guest` | `meteo` / `changeme` | `RABBITMQ_USER=meteo`、`RABBITMQ_PASSWORD=changeme` |
| InfluxDB token | 脚本调用 setup 时生成，保存于 `tools/influx-token.txt` | `changeme-influx-admin-token` | `INFLUX_TOKEN=changeme-influx-admin-token` |
| MQTT | Mosquitto | EMQX（协议兼容，后端配置无需改动） | — |

> 建议在 `deploy/.env` 中把上面的密码改成与方案 A 一致的值，或直接以对应环境变量启动后端，二者取其一即可。

### 注意事项二：compose 只含中间件，且未设就绪等待

- compose 只声明 5 个中间件，**后端与前端不在其中**，仍需按 `README.md` 的「快速开始」单独启动。
- 文件内没有 `healthcheck` 与 `depends_on`，中间件启动需要数十秒；后端若在其中初始化完成前启动，
  MySQL / InfluxDB 连接会失败。请等容器全部就绪后再启动后端（可用 `docker compose ps` 确认）。

启动后包含：

| 服务 | 端口 | 控制台/说明 |
|---|---|---|
| MySQL 8 | 3306 | 库名 meteo，应用账号 meteo |
| Redis 7 | 6379 | — |
| RabbitMQ 3.12 | 5672 / 15672 | 管理台 http://localhost:15672（meteo/changeme） |
| InfluxDB 2.7 | 8086 | 控制台 http://localhost:8086，org=meteo，bucket=meteo |
| EMQX 5 | 1883 / 18083 | Dashboard http://localhost:18083（admin/public） |

## 环境变量

建议在 `deploy/.env`（已被 .gitignore 忽略）中覆盖默认密码：

```
MYSQL_ROOT_PASSWORD=changeme
MYSQL_PASSWORD=changeme
RABBITMQ_PASSWORD=changeme
INFLUX_PASSWORD=changeme123
INFLUX_TOKEN=changeme-influx-admin-token
```

## 后端连接配置

后端 `application-dev.yml` 中的连接参数通过同名环境变量覆盖，默认值与本 compose 文件一致。
