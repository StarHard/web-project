# 部署环境说明

本地开发有两条路径，按机器条件选择：

| 方案 | 适用场景 | 入口 |
|---|---|---|
| **A. 便携式脚本**（推荐无 Docker 环境） | 本机直接跑，中间件解压到 `tools/`，无需 Docker，删除目录即卸载 | `scripts/start-all.ps1` |
| **B. Docker Compose** | 有 Docker Desktop，环境与生产部署一致 | `deploy/docker-compose.yml` |

---

## 方案 A：便携式一键启动（Windows）

```powershell
# 首次运行（自动下载并解压全部中间件，约 270MB，需数分钟）
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1

# 带演示数据（注入 7 天历史观测，便于查看预报与准确率检验）
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -Seed

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
| `scripts/seed-demo-data.ps1` | 注入 7 天逐小时历史观测（含日变化曲线），供预报与检验演示 |

说明：

- **MySQL 不在脚本管理范围**：使用本机已装的 MySQL/MariaDB（XAMPP 亦可），脚本只负责创建 `meteo` 库，建表由 Flyway 在服务启动时自动完成。
- **数据全在仓库内**：InfluxDB 数据目录被显式指定为 `tools/influxdb/data`（默认是 `%USERPROFILE%\.influxdbv2`，已避免），删除 `tools/` 即彻底清理。
- **唯一例外**：Mosquitto 官方安装包会注册一个 Windows 服务 `mosquitto`（开机自启）。`setup-middleware.ps1` 在有管理员权限时会自动移除；否则请手动执行：
  ```powershell
  sc stop mosquitto & sc delete mosquitto
  ```
- **端口占用**：8080 后端、6379 Redis、8086 InfluxDB、1883 MQTT、5672 RabbitMQ。Mosquitto 与 EMQX 协议兼容，生产环境按架构文档使用 EMQX。

---

## 方案 B：Docker Compose

```bash
cd deploy
docker compose up -d
```

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
