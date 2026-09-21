<#
.SYNOPSIS
  一键启动本地开发环境：中间件 + 后端服务

.DESCRIPTION
  按顺序完成：
    1. 准备中间件（首次运行会下载，见 setup-middleware.ps1）
    2. 启动 Redis / InfluxDB / Mosquitto(MQTT) / RabbitMQ（已在运行的自动跳过）
    3. 初始化 InfluxDB（org、bucket、token；token 保存在 tools/influx-token.txt）
    4. 确保 MySQL 业务库存在（库名默认 meteo）
    5. 启动后端 Spring Boot 服务（使用 Maven Wrapper，无需预装 Maven）
  日志输出到 tools/app.log；停止请执行 scripts\stop-all.ps1

.PARAMETER MysqlUser
  MySQL 账号，默认 root

.PARAMETER MysqlPassword
  MySQL 密码，默认 123456

.PARAMETER MysqlPort
  MySQL 端口，默认 3306

.PARAMETER SkipSetup
  跳过中间件下载/解压步骤（中间件已准备就绪时使用）

.PARAMETER Seed
  启动后注入 7 天历史观测演示数据（便于查看预报与准确率检验效果）

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
  powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -MysqlPassword 你的密码 -Seed
#>
param(
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "123456",
    [int]$MysqlPort = 3306,
    [switch]$SkipSetup,
    [switch]$Seed
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root    = Split-Path $PSScriptRoot -Parent
$Tools   = Join-Path $Root "tools"
$Backend = Join-Path $Root "backend"
$AppLog  = Join-Path $Tools "app.log"
$AppErr  = Join-Path $Tools "app.err.log"
$TokenFile = Join-Path $Tools "influx-token.txt"
# 大模型密钥文件（tools/ 已 gitignore，不入库）：内容为一行 DeepSeek API Key
$LlmKeyFile = Join-Path $Tools "llm-key.txt"

$InfluxUser = "admin"
$InfluxPassword = "MeteoAdmin@2026"
$InfluxToken = "meteo-dev-token-000000000000000000000000"   # 长度需 >= 8 位，开发环境固定值便于团队共享
$InfluxOrg = "meteo"
$InfluxBucket = "meteo"

New-Item -ItemType Directory -Force -Path $Tools | Out-Null

function Test-Port {
    param([int]$Port)
    try {
        $client = New-Object Net.Sockets.TcpClient
        $client.Connect("127.0.0.1", $Port)
        $client.Close()
        return $true
    } catch {
        return $false
    }
}

function Wait-Port {
    param([int]$Port, [int]$TimeoutSec = 90, [string]$Name = "")
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Milliseconds 800
    }
    Write-Host "[警告] $Name 端口 $Port 在 $TimeoutSec 秒内未就绪" -ForegroundColor Yellow
    return $false
}

# 去除不可见格式控制字符（U+200B-U+200F / U+202A-U+202E / U+2066-U+2069）
# 从网页或文档复制环境变量值时极易带入这类字符，会导致路径无法识别
function Repair-PathString {
    param([string]$Value)
    if ([string]::IsNullOrEmpty($Value)) { return $Value }
    return ($Value -replace '[\u200B-\u200F\u202A-\u202E\u2066-\u2069]', '').Trim()
}

# 解析可用的 JAVA_HOME
# 注意：Get-Command java 常指向 Oracle javapath 垫片，其上级目录并非有效 JDK，
# 因此优先通过 java -XshowSettings 读取真实 java.home，再回退到常见安装目录
function Resolve-JavaHome {
    $candidates = @(Repair-PathString $env:JAVA_HOME)

    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd) {
        try {
            $props = & $javaCmd.Source -XshowSettings:properties -version 2>&1 | Out-String
            $m = [regex]::Match($props, "java\.home\s*=\s*(.+)")
            if ($m.Success) { $candidates += Repair-PathString $m.Groups[1].Value }
        } catch { }
    }

    foreach ($base in @("C:\Program Files\Java")) {
        if (Test-Path -LiteralPath (Join-Path $base "bin\java.exe")) { $candidates += $base }
        Get-ChildItem $base -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            if (Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe")) { $candidates += $_.FullName }
        }
    }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath (Join-Path $c "bin\java.exe") -ErrorAction SilentlyContinue)) {
            return $c
        }
    }
    return $null
}

# ---------------- 1. 准备中间件 ----------------
if (-not $SkipSetup) {
    Write-Host "== 1/5 准备中间件 ==" -ForegroundColor White
    & (Join-Path $PSScriptRoot "setup-middleware.ps1")
} else {
    Write-Host "== 1/5 跳过中间件准备（-SkipSetup）==" -ForegroundColor DarkGray
}

# ---------------- 2. 启动中间件 ----------------
Write-Host "== 2/5 启动中间件 ==" -ForegroundColor White

# Redis
if (Test-Port 6379) {
    Write-Host "  Redis 已运行 (:6379)"
} else {
    Start-Process -FilePath (Join-Path $Tools "redis\redis-server.exe") `
        -ArgumentList "--port", "6379" -WorkingDirectory (Join-Path $Tools "redis") -WindowStyle Hidden
    Wait-Port 6379 20 "Redis" | Out-Null
    Write-Host "  Redis 已启动 (:6379)" -ForegroundColor Green
}

# InfluxDB
# 显式指定数据目录到 tools/ 下：默认路径为 %USERPROFILE%\.influxdbv2，
# 会把数据写到用户目录，无法随 tools/ 一起删除，不符合便携化要求
if (Test-Port 8086) {
    Write-Host "  InfluxDB 已运行 (:8086)"
} else {
    $influxData = Join-Path $Tools "influxdb\data"
    New-Item -ItemType Directory -Force -Path $influxData | Out-Null
    Start-Process -FilePath (Join-Path $Tools "influxdb\influxd.exe") `
        -ArgumentList "--bolt-path", (Join-Path $influxData "influxd.bolt"),
                      "--engine-path", (Join-Path $influxData "engine"),
                      "--sqlite-path", (Join-Path $influxData "influxd.sqlite") `
        -WorkingDirectory (Join-Path $Tools "influxdb") -WindowStyle Hidden
    Wait-Port 8086 60 "InfluxDB" | Out-Null
    Write-Host "  InfluxDB 已启动 (:8086，数据目录 tools\influxdb\data)" -ForegroundColor Green
}

# Mosquitto (MQTT)
if (Test-Port 1883) {
    Write-Host "  MQTT Broker 已运行 (:1883)"
} else {
    Start-Process -FilePath (Join-Path $Tools "mosquitto\mosquitto.exe") `
        -ArgumentList "-c", (Join-Path $Tools "mosquitto\mosquitto.conf") `
        -WorkingDirectory (Join-Path $Tools "mosquitto") -WindowStyle Hidden
    Wait-Port 1883 20 "Mosquitto" | Out-Null
    Write-Host "  MQTT Broker 已启动 (:1883)" -ForegroundColor Green
}

# RabbitMQ（需 Erlang 运行时）
if (Test-Port 5672) {
    Write-Host "  RabbitMQ 已运行 (:5672)"
} else {
    $rabbitHome = (Get-ChildItem (Join-Path $Tools "rabbitmq") -Directory -Filter "rabbitmq_server-*" |
        Select-Object -First 1).FullName
    if (-not $rabbitHome) { throw "未找到 RabbitMQ，请先执行 scripts\setup-middleware.ps1" }
    $env:ERLANG_HOME = Join-Path $Tools "erlang"
    $env:RABBITMQ_BASE = Join-Path $Tools "rabbitmq-data"
    $env:PATH = "$(Join-Path $Tools 'erlang\bin');$env:PATH"
    New-Item -ItemType Directory -Force -Path $env:RABBITMQ_BASE | Out-Null
    Write-Host "  RabbitMQ 启动中（首次约需 30-60 秒）..."
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$rabbitHome\sbin\rabbitmq-server.bat`" -detached" `
        -WorkingDirectory $rabbitHome -WindowStyle Hidden
    Wait-Port 5672 120 "RabbitMQ" | Out-Null
    Write-Host "  RabbitMQ 已启动 (:5672)" -ForegroundColor Green
}

# ---------------- 3. 初始化 InfluxDB ----------------
Write-Host "== 3/5 初始化 InfluxDB ==" -ForegroundColor White
if (Test-Path $TokenFile) {
    $InfluxToken = (Get-Content $TokenFile -Raw).Trim()
    Write-Host "  复用已有 token：$TokenFile"
} else {
    $body = @{ username = $InfluxUser; password = $InfluxPassword; org = $InfluxOrg;
               bucket = $InfluxBucket; token = $InfluxToken } | ConvertTo-Json
    try {
        $resp = Invoke-RestMethod -Uri "http://localhost:8086/api/v2/setup" -Method Post `
            -Body $body -ContentType "application/json"
        $InfluxToken = $resp.auth.token
        Write-Host "  已完成 setup，org=$InfluxOrg bucket=$InfluxBucket" -ForegroundColor Green
    } catch {
        Write-Host "  [警告] setup 失败（可能已初始化过）。请手动指定 token：" -ForegroundColor Yellow
        Write-Host "         访问 http://localhost:8086 登录后在 Load Data → API Tokens 复制 token，" -ForegroundColor Yellow
        Write-Host "         写入文件 $TokenFile 后重新运行本脚本。" -ForegroundColor Yellow
        throw
    }
    $InfluxToken | Out-File -FilePath $TokenFile -Encoding ascii -NoNewline
    Write-Host "  token 已保存：$TokenFile"
}

# ---------------- 4. 确保 MySQL 业务库 ----------------
Write-Host "== 4/5 检查 MySQL 业务库 ==" -ForegroundColor White
$mysqlExe = (Get-Command mysql.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $mysqlExe) {
    $candidates = @(
        "C:\xampp\mysql\bin\mysql.exe",
        (Get-ChildItem "C:\Program Files\MySQL" -Recurse -Filter "mysql.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName)
    ) | Where-Object { $_ -and (Test-Path $_) }
    $mysqlExe = $candidates | Select-Object -First 1
}
if ($mysqlExe) {
    & $mysqlExe -h 127.0.0.1 -P $MysqlPort -u $MysqlUser -p"$MysqlPassword" `
        -e "CREATE DATABASE IF NOT EXISTS meteo CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  业务库 meteo 就绪（建表由 Flyway 在服务启动时自动完成）" -ForegroundColor Green
    } else {
        Write-Host "  [警告] 无法连接 MySQL，请确认账号密码与端口（库需手动创建）" -ForegroundColor Yellow
    }
} else {
    Write-Host "  [警告] 未找到 mysql 客户端，请手动执行：" -ForegroundColor Yellow
    Write-Host "         CREATE DATABASE IF NOT EXISTS meteo CHARACTER SET utf8mb4;" -ForegroundColor Yellow
}

# ---------------- 5. 启动后端 ----------------
Write-Host "== 5/5 启动后端服务 ==" -ForegroundColor White

if (Test-Port 8080) {
    Write-Host "  后端已在运行 (:8080)，跳过启动" -ForegroundColor Yellow
} else {
    $javaHome = Resolve-JavaHome
    if ($javaHome) {
        if ($javaHome -ne $env:JAVA_HOME) {
            Write-Host "  JAVA_HOME 已修正为：$javaHome"
        } else {
            Write-Host "  JAVA_HOME=$javaHome"
        }
        $env:JAVA_HOME = $javaHome
    } else {
        throw "未找到可用的 JDK。请安装 JDK 17 或更高版本，并配置正确的 JAVA_HOME 环境变量"
    }
    $env:MYSQL_HOST = "127.0.0.1"
    $env:MYSQL_PORT = "$MysqlPort"
    $env:MYSQL_DB = "meteo"
    $env:MYSQL_USER = $MysqlUser
    $env:MYSQL_PASSWORD = $MysqlPassword
    $env:INFLUX_TOKEN = $InfluxToken
    $env:RABBITMQ_USER = "guest"
    $env:RABBITMQ_PASSWORD = "guest"

    # 大模型密钥：优先用环境变量 LLM_API_KEY，其次读 tools\llm-key.txt
    # 两者都没有时不阻断启动——助手接口会降级为 source=unavailable 的提示，其它功能不受影响
    if (-not $env:LLM_API_KEY -and (Test-Path $LlmKeyFile)) {
        $env:LLM_API_KEY = (Get-Content $LlmKeyFile -Raw).Trim()
        Write-Host "  大模型密钥：已从 $LlmKeyFile 载入"
    } elseif ($env:LLM_API_KEY) {
        Write-Host "  大模型密钥：使用环境变量 LLM_API_KEY"
    } else {
        Write-Host "  大模型密钥：未配置，智能助手将降级为提示信息" -ForegroundColor Yellow
    }

    Write-Host "  正在启动 Spring Boot（首次运行需下载 Maven 与项目依赖，可能耗时数分钟）..."
    Start-Process -FilePath (Join-Path $Backend "mvnw.cmd") -ArgumentList "spring-boot:run" `
        -WorkingDirectory $Backend -WindowStyle Hidden `
        -RedirectStandardOutput $AppLog -RedirectStandardError $AppErr

    # 首次运行需下载 Maven 发行包与全部依赖，超时留足 10 分钟
    if (Wait-Port 8080 600 "后端服务") {
        Write-Host "  后端已启动" -ForegroundColor Green
    } else {
        Write-Host "  [错误] 后端启动失败，请查看日志：$AppLog / $AppErr" -ForegroundColor Red
        exit 1
    }
}

# ---------------- 可选：注入演示数据 ----------------
if ($Seed) {
    Write-Host "== 注入演示数据 ==" -ForegroundColor White
    Start-Sleep -Seconds 8   # 等待采集链路与 Flyway 就绪
    & (Join-Path $PSScriptRoot "seed-demo-data.ps1")
}

Write-Host ""
Write-Host "环境就绪！" -ForegroundColor Green
Write-Host "  接口地址   : http://localhost:8080/api/v1"
Write-Host "  接口文档   : http://localhost:8080/api/v1/swagger-ui.html"
Write-Host "  InfluxDB   : http://localhost:8086"
Write-Host "  演示账号   : admin / Admin@123"
Write-Host "  运行日志   : $AppLog"
Write-Host "  停止环境   : powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1"