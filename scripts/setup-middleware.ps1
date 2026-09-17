<#
.SYNOPSIS
  下载并解压本地开发所需中间件到 tools/ 目录（便携式，不注册系统服务）

.DESCRIPTION
  一次性准备以下组件，全部解压到仓库 tools/ 下，删除该目录即可完全卸载，对系统零侵入：
    - Redis 5.0.14        （缓存 / JWT 黑名单 / 幂等去重）
    - InfluxDB 2.7.10     （气象时序数据、预报产品）
    - Mosquitto 2.0.20    （MQTT Broker，与 EMQX 协议兼容）
    - Erlang 26 + RabbitMQ 3.13.7（Agent 间消息队列）
  MySQL 不在本脚本范围：请使用本机已安装的 MySQL/MariaDB（XAMPP 亦可）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\setup-middleware.ps1
  powershell -ExecutionPolicy Bypass -File scripts\setup-middleware.ps1 -Force   # 强制重新下载
#>
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # 关闭进度条可显著加快下载

$Root   = Split-Path $PSScriptRoot -Parent
$Tools  = Join-Path $Root "tools"
$Dl     = Join-Path $Tools "downloads"

# NSIS 静默安装要求 /D= 路径不能带引号，含空格会失败
if ($Tools -match '\s') {
    Write-Host "[警告] 仓库路径包含空格，Mosquitto/Erlang 自动安装可能失败：$Tools" -ForegroundColor Yellow
    Write-Host "       建议将项目移动到无空格路径后重试。" -ForegroundColor Yellow
}

New-Item -ItemType Directory -Force -Path $Tools, $Dl | Out-Null

# 组件下载源（如失效可手动下载后放入 tools/downloads 同名文件）
$Sources = [ordered]@{
    "redis.zip"      = "https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip"
    "influxdb.zip"   = "https://download.influxdata.com/influxdb/releases/influxdb2-2.7.10-windows.zip"
    "mosquitto.exe"  = "https://mosquitto.org/files/binary/win64/mosquitto-2.0.20-install-windows-x64.exe"
    "erlang.exe"     = "https://github.com/erlang/otp/releases/download/OTP-26.2.5/otp_win64_26.2.5.exe"
    "rabbitmq.zip"   = "https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.13.7/rabbitmq-server-windows-3.13.7.zip"
}

function Download-IfMissing {
    param([string]$Name, [string]$Url)
    $target = Join-Path $Dl $Name
    if ((Test-Path $target) -and -not $Force) {
        Write-Host "  已存在，跳过下载：$Name"
        return $target
    }
    Write-Host "  下载 $Name ..." -ForegroundColor Cyan
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $Url -OutFile $target -UseBasicParsing
    $size = [Math]::Round((Get-Item $target).Length / 1MB, 1)
    Write-Host "  完成：$Name ($size MB)" -ForegroundColor Green
    return $target
}

function Extract-Zip {
    param([string]$Zip, [string]$Dest, [string]$Marker)
    if ((Test-Path (Join-Path $Dest $Marker)) -and -not $Force) {
        Write-Host "  已解压，跳过：$Dest"
        return
    }
    Write-Host "  解压到 $Dest ..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Expand-Archive -Path $Zip -DestinationPath $Dest -Force
}

function Install-Nsis {
    param([string]$Exe, [string]$Dest, [string]$Marker, [string]$Label)
    if ((Test-Path (Join-Path $Dest $Marker)) -and -not $Force) {
        Write-Host "  已安装，跳过：$Label"
        return
    }
    Write-Host "  静默安装 $Label 到 $Dest ..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    $p = Start-Process -FilePath $Exe -ArgumentList "/S", "/D=$Dest" -Wait -PassThru
    if ($p.ExitCode -ne 0) {
        throw "$Label 安装失败，退出码 $($p.ExitCode)"
    }
}

Write-Host "== 1/5 Redis ==" -ForegroundColor White
Extract-Zip -Zip (Download-IfMissing "redis.zip" $Sources["redis.zip"]) `
            -Dest (Join-Path $Tools "redis") -Marker "redis-server.exe"

Write-Host "== 2/5 InfluxDB ==" -ForegroundColor White
Extract-Zip -Zip (Download-IfMissing "influxdb.zip" $Sources["influxdb.zip"]) `
            -Dest (Join-Path $Tools "influxdb") -Marker "influxd.exe"

Write-Host "== 3/5 Mosquitto (MQTT) ==" -ForegroundColor White
Install-Nsis -Exe (Download-IfMissing "mosquitto.exe" $Sources["mosquitto.exe"]) `
             -Dest (Join-Path $Tools "mosquitto") -Marker "mosquitto.exe" -Label "Mosquitto"

# Mosquitto 安装包会注册名为 mosquitto 的 Windows 服务（开机自启），与便携化目标冲突。
# 尝试移除该服务，改为由 start-all.ps1 按需启动进程；无管理员权限时给出明确指引。
$mqService = Get-Service -Name "mosquitto" -ErrorAction SilentlyContinue
if ($mqService) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())
        .IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($isAdmin) {
        & sc.exe stop mosquitto | Out-Null
        Start-Sleep -Seconds 2
        & sc.exe delete mosquitto | Out-Null
        Write-Host "  已移除 Mosquitto 自启动服务，改为按需启动" -ForegroundColor Green
    } else {
        Write-Host "  [提示] 安装包注册了 Windows 服务 mosquitto（开机自启、普通权限无法停止）。" -ForegroundColor Yellow
        Write-Host "         推荐用管理员权限执行以下命令移除，改为按需启动：" -ForegroundColor Yellow
        Write-Host "           sc stop mosquitto & sc delete mosquitto" -ForegroundColor Yellow
        Write-Host "         若不处理，服务会随开机自动运行（占用约 5MB 内存，功能不受影响）。" -ForegroundColor Yellow
    }
}

Write-Host "== 4/5 Erlang（RabbitMQ 运行时依赖）==" -ForegroundColor White
Install-Nsis -Exe (Download-IfMissing "erlang.exe" $Sources["erlang.exe"]) `
             -Dest (Join-Path $Tools "erlang") -Marker "bin\erl.exe" -Label "Erlang"

Write-Host "== 5/5 RabbitMQ ==" -ForegroundColor White
Extract-Zip -Zip (Download-IfMissing "rabbitmq.zip" $Sources["rabbitmq.zip"]) `
            -Dest (Join-Path $Tools "rabbitmq") -Marker "rabbitmq_server-3.13.7\sbin\rabbitmq-server.bat"

# Mosquitto 配置：开发环境允许匿名接入，监听 1883
$mqConf = Join-Path $Tools "mosquitto\mosquitto.conf"
if (-not (Test-Path $mqConf) -or $Force) {
    @"
listener 1883
allow_anonymous true
"@ | Out-File -FilePath $mqConf -Encoding ascii
    Write-Host "  已生成 Mosquitto 配置：$mqConf"
}

Write-Host ""
Write-Host "中间件准备完成。下一步执行：scripts\start-all.ps1" -ForegroundColor Green