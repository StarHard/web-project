<#
.SYNOPSIS
  向 InfluxDB 注入历史观测演示数据（含日变化曲线）

.DESCRIPTION
  生成指定天数、逐小时、多站点的观测数据（气温日变化、湿度反相、白天辐射钟形曲线、
  约 12% 时次降水），直接写入 InfluxDB obs_min（qc_flag=passed）。
  用途：让「精细化预报」与「预报准确率检验（MAE/RMSE/TS）」在演示时有真实可信的数据基础。

  注意：数据为按气象规律合成的演示数据，不用于科研结论。

.PARAMETER Days
  生成天数，默认 7 天（越大则小时气候态越稳定）

.PARAMETER Stations
  站点编码列表，需与数据库 station 表中的 station_code 一致

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\seed-demo-data.ps1
  powershell -ExecutionPolicy Bypass -File scripts\seed-demo-data.ps1 -Days 14
#>
param(
    [int]$Days = 7,
    [string[]]$Stations = @("CAMPUS01", "FARM02")
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Root      = Split-Path $PSScriptRoot -Parent
$Tools     = Join-Path $Root "tools"
$TokenFile = Join-Path $Tools "influx-token.txt"
$SeedFile  = Join-Path $Tools "seed.lp"

if (-not (Test-Path $TokenFile)) {
    throw "未找到 InfluxDB token：$TokenFile，请先执行 scripts\start-all.ps1"
}
$token = (Get-Content $TokenFile -Raw).Trim()

$sb  = New-Object System.Text.StringBuilder
$now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$rand = New-Object System.Random(42)   # 固定种子，确保团队间生成的数据一致

$totalHours = $Days * 24
for ($h = $totalHours; $h -ge 0; $h--) {
    $ts  = $now - ($h * 3600)
    $dt  = [DateTimeOffset]::FromUnixTimeSeconds($ts).ToLocalTime()
    $hourOfDay = $dt.Hour
    $phase = 2 * [Math]::PI * ($hourOfDay - 9) / 24   # 15 时气温最高

    foreach ($station in $Stations) {
        $offset = if ($station -eq "FARM02") { 1.2 } else { 0 }   # 农业站略偏暖
        $temp = 23 + 5 * [Math]::Sin($phase) + $offset + ($rand.NextDouble() * 0.6 - 0.3)
        $humi = 65 - 20 * [Math]::Sin($phase) + ($rand.NextDouble() * 3 - 1.5)
        $pres = 1010 + ($rand.NextDouble() * 4 - 2)
        $wind = [Math]::Max(0, 3.5 + 2 * [Math]::Sin($phase * 1.5) + ($rand.NextDouble() * 2 - 1))
        $dir  = (190 + $rand.NextDouble() * 40) % 360
        $rain = if ($rand.NextDouble() -lt 0.12) { [Math]::Round($rand.NextDouble() * 3, 1) } else { 0 }
        $rad  = if ($hourOfDay -ge 6 -and $hourOfDay -le 18) {
            [Math]::Round(700 * [Math]::Sin([Math]::PI * ($hourOfDay - 6) / 12), 1)
        } else { 0 }
        $vis  = [Math]::Round(12 + $rand.NextDouble() * 10, 1)
        $evap = [Math]::Round(0.2 + $rand.NextDouble() * 0.5, 2)

        $line = "obs_min,station_code=$station,qc_flag=passed temp={0:F1},humi={1:F1},pres={2:F1},wind_speed={3:F1},wind_dir={4:F0},rain={5:F1},rad={6:F1},vis={7:F1},evap={8:F2} {9}" -f `
            $temp, $humi, $pres, $wind, $dir, $rain, $rad, $vis, $evap, $ts
        [void]$sb.Append($line + "`n")   # 必须使用 LF，行协议不接受 CRLF
    }
}

$payload = $sb.ToString()
$payload | Out-File $SeedFile -Encoding ascii -NoNewline
$lines = ($payload -split "`n").Count
Write-Host "  已生成 $lines 条观测（$Days 天 × $($Stations.Count) 站点）"

$resp = curl.exe -s -X POST "http://localhost:8086/api/v2/write?org=meteo&bucket=meteo&precision=s" `
    -H "Authorization: Token $token" `
    -H "Content-Type: text/plain; charset=utf-8" `
    --data-binary "@$SeedFile"

if ([string]::IsNullOrWhiteSpace($resp)) {
    Write-Host "  写入 InfluxDB 成功" -ForegroundColor Green
    Write-Host ""
    Write-Host "后续建议：" -ForegroundColor White
    Write-Host "  1) 调用预报回算产出检验样本：POST /api/v1/forecasts/backtest?days=3"
    Write-Host "  2) 查看准确率检验结果：GET  /api/v1/forecasts/verification?stationCode=CAMPUS01&days=7"
} else {
    Write-Host "  [错误] 写入失败：$resp" -ForegroundColor Red
    exit 1
}