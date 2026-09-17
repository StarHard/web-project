<#
.SYNOPSIS
  停止本地开发环境：后端服务与全部中间件

.DESCRIPTION
  按端口定位进程并结束，覆盖：后端(8080)、Redis(6379)、InfluxDB(8086)、
  Mosquitto(1883)、RabbitMQ(5672)。执行前会打印将结束的进程，便于确认。
  tools/ 目录与数据均保留，下次执行 start-all.ps1 可快速恢复。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -Force   # 跳过确认
#>
param(
    [switch]$Force
)

$ErrorActionPreference = "Continue"

# 端口与对应组件的映射（使用对象列表，避免哈希表键类型带来的取值问题）
$Targets = @(
    [pscustomobject]@{ Port = 8080; Label = "后端服务" },
    [pscustomobject]@{ Port = 6379; Label = "Redis" },
    [pscustomobject]@{ Port = 8086; Label = "InfluxDB" },
    [pscustomobject]@{ Port = 1883; Label = "MQTT Broker (Mosquitto)" },
    [pscustomobject]@{ Port = 5672; Label = "RabbitMQ" }
)

$found = @()
foreach ($target in $Targets) {
    $conn = Get-NetTCPConnection -LocalPort $target.Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($conn) {
        $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($proc) {
            $found += [pscustomobject]@{
                Port    = $target.Port
                Label   = $target.Label
                Pid     = $proc.Id
                Process = $proc.ProcessName
            }
        }
    }
}

if ($found.Count -eq 0) {
    Write-Host "没有检测到运行中的服务，无需操作。" -ForegroundColor Green
    exit 0
}

Write-Host "将结束以下进程：" -ForegroundColor White
$found | Format-Table Port, Label, Pid, Process -AutoSize | Out-String | Write-Host

if (-not $Force) {
    $answer = Read-Host "确认停止？(y/N)"
    if ($answer -ne "y" -and $answer -ne "Y") {
        Write-Host "已取消。"
        exit 0
    }
}

function Stop-ByPid {
    param([int]$ProcessId, [string]$Label)
    # Mosquitto 若由 Windows 服务托管，Stop-Process/taskkill 会因权限被拒，需走服务停止
    if ($Label -like "MQTT*") {
        $mqService = Get-Service -Name "mosquitto" -ErrorAction SilentlyContinue
        if ($mqService -and $mqService.Status -eq "Running") {
            try {
                Stop-Service -Name mosquitto -Force -ErrorAction Stop
                Write-Host "  已停止 $Label（Windows 服务 mosquitto）" -ForegroundColor Green
                return $true
            } catch {
                Write-Host "  [提示] $Label 由 Windows 服务托管，需管理员权限才能停止。" -ForegroundColor Yellow
                Write-Host "         建议用管理员权限执行：sc stop mosquitto & sc delete mosquitto" -ForegroundColor Yellow
                Write-Host "         移除服务后，本脚本即可像其他组件一样按需启停。" -ForegroundColor Yellow
                return $false
            }
        }
    }
    try {
        Stop-Process -Id $ProcessId -Force -ErrorAction Stop
        Write-Host "  已停止 $Label (PID $ProcessId)" -ForegroundColor Green
        return $true
    } catch {
        # 兜底：部分进程对 Stop-Process 返回 Access denied，改用 taskkill 强制结束进程树
        $null = & taskkill.exe /PID $ProcessId /F /T 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  已停止 $Label (PID $ProcessId，taskkill)" -ForegroundColor Green
            return $true
        }
        Write-Host "  [警告] 停止 $Label (PID $ProcessId) 失败，请手动在任务管理器结束" -ForegroundColor Yellow
        return $false
    }
}

foreach ($item in $found) {
    Stop-ByPid -ProcessId $item.Pid -Label $item.Label | Out-Null
}

# RabbitMQ 的 Erlang 节点与端口映射守护进程可能仍驻留，一并清理
$leftover = Get-Process -Name "beam.smp", "epmd" -ErrorAction SilentlyContinue
foreach ($proc in $leftover) {
    Stop-ByPid -ProcessId $proc.Id -Label "残留 Erlang 进程 $($proc.ProcessName)" | Out-Null
}

# 复核端口状态
Start-Sleep -Seconds 2
$stillUp = @()
foreach ($target in $Targets) {
    if (Get-NetTCPConnection -LocalPort $target.Port -State Listen -ErrorAction SilentlyContinue) {
        $stillUp += "$($target.Label):$($target.Port)"
    }
}

Write-Host ""
if ($stillUp.Count -eq 0) {
    Write-Host "环境已停止，所有端口已释放。" -ForegroundColor Green
} else {
    Write-Host "[警告] 以下端口仍被占用：$($stillUp -join ', ')" -ForegroundColor Yellow
}
Write-Host "日志与数据保留在 tools 目录，可执行 scripts\start-all.ps1 重新启动。"