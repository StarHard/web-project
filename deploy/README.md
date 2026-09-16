# 部署环境说明

## 中间件一键启动

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
