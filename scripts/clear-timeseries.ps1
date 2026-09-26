<#
.SYNOPSIS
  清空 InfluxDB 中的时序数据（观测与预报产品），用于重置演示数据基线

.DESCRIPTION
  删除 meteo 桶里 obs_min / obs_hour / fcst 三个 measurement 的全部数据。

  为什么需要它：数据模拟器的物理模型在 2026-09-27 做过一次重写（原实现是各要素独立的
  随机游走，辐射与时刻无关、降水逐条独立掷骰子；新实现改为「要素是时刻的连续函数」，
  含日变化、季节与要素耦合）。新旧两套数据在同一段时间序列里物理性格完全不同——
  旧数据深夜气温可到 31.6℃、气压 990.9，新模型同期约 21.9℃、1012.7。
  质控的时间一致性检验会如实把新数据判为可疑（差值确实超限），导致质控通过的数据
  长时间冻结、审核队列被垃圾任务刷满。因此需要把旧数据整体清掉、重新灌入。

  清空后只需重启后端（scripts\start-all.ps1）：后端启动时会用与实时相同的物理模型
  自动回填 7 天逐小时历史观测（见 DataSimulator.backfillHistoryIfEmpty），
  不再需要单独的灌数脚本。

.PARAMETER Force
  跳过确认提示

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\clear-timeseries.ps1
#>
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root      = Split-Path $PSScriptRoot -Parent
$Tools     = Join-Path $Root "tools"
$TokenFile = Join-Path $Tools "influx-token.txt"
$BodyFile  = Join-Path $Tools "clear-timeseries.json"

if (-not (Test-Path $TokenFile)) {
    throw "未找到 InfluxDB token：$TokenFile，请先执行 scripts\start-all.ps1"
}
$token = (Get-Content $TokenFile -Raw).Trim()

Write-Host "将删除 meteo 桶中以下 measurement 的全部数据：" -ForegroundColor White
Write-Host "  obs_min / obs_hour（观测时序）、fcst（预报产品）" -ForegroundColor White
Write-Host "  影响：历史趋势、统计报表、预报检验样本都会被清空，重启后端会自动回填" -ForegroundColor Yellow

if (-not $Force) {
    $answer = Read-Host "确认清空？(y/N)"
    if ($answer -ne "y" -and $answer -ne "Y") {
        Write-Host "已取消。"
        exit 0
    }
}

# 删除接口要求 start/stop；start 取 1970 即覆盖全部历史，stop 留一点余量到当前时刻之后
$stopIso = [DateTimeOffset]::UtcNow.AddMinutes(5).ToString("yyyy-MM-ddTHH:mm:ssZ")
# 注意：InfluxDB 2 的 delete 接口的 predicate 只支持 AND，不支持 OR。
# 把三个 measurement 用 OR 拼成一条 predicate 会被拒绝：
#   {"code":"invalid","message":"error decoding json body: the logical operator OR is not supported yet"}
# 因此逐个 measurement 各发一次删除请求。
$measurements = @("obs_min", "obs_hour", "fcst")
$failed = @()

foreach ($measurement in $measurements) {
    $body = [ordered]@{
        start     = "1970-01-01T00:00:00Z"
        stop      = $stopIso
        predicate = "_measurement=""$measurement"""
    } | ConvertTo-Json -Compress

    # 用文件传 body，避免 PowerShell 与 curl 的引号转义互相干扰（与 seed 脚本同样的做法）
    $body | Out-File $BodyFile -Encoding ascii -NoNewline

    $resp = curl.exe -s -X POST "http://localhost:8086/api/v2/delete?org=meteo&bucket=meteo" `
        -H "Authorization: Token $token" `
        -H "Content-Type: application/json" `
        --data-binary "@$BodyFile"

    if ([string]::IsNullOrWhiteSpace($resp)) {
        Write-Host "  已删除 $measurement" -ForegroundColor Green
    } else {
        Write-Host "  [错误] 删除 $measurement 失败：$resp" -ForegroundColor Red
        $failed += $measurement
    }
}

if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "  以下 measurement 删除失败：$($failed -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "  时序数据已清空" -ForegroundColor Green
Write-Host ""
Write-Host "后续步骤：" -ForegroundColor White
Write-Host "  重启后端即可自动回填 7 天历史观测：scripts\start-all.ps1"