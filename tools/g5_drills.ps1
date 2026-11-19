# Gate G5 崩溃恢复演练 ×3（PowerShell 版；本机没有 bash，等价于 tools/g5_drills.sh）
#
# 每轮：等干净 LOBBY -> restart_probe before（开局到 PLAY + 落盘 + stop）
#       -> 重启服务端 -> restart_probe after（逐字段比对 + abort）
# 跑完最后一轮 after 会把局 abort 回 LOBBY，服务端保持运行。
#
# 用法：pwsh -File tools/g5_drills.ps1
#       $env:PY 可覆盖 python 路径

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$py = if ($env:PY) { $env:PY } else { "run\py-embed\python.exe" }
$env:RCON_PORT = "25576"
$env:RCON_PASSWORD = "nbwm0"
$env:PYTHONIOENCODING = "utf-8"
$log = "run\server-console.log"
$drillLog = "run\g5_drills.log"
"" | Set-Content $drillLog -Encoding utf8

function Log([string]$msg) {
    $msg | Tee-Object -FilePath $drillLog -Append | Out-Host
}

function Wait-PortDown {
    for ($i = 0; $i -lt 60; $i++) {
        if (-not (netstat -ano | Select-String ":25576 .*LISTENING")) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Wait-Ready {
    for ($i = 0; $i -lt 120; $i++) {
        $c = Get-Content $log -Raw -ErrorAction SilentlyContinue
        if ($c -and $c -match "Done \(") { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Start-Server {
    Remove-Item $log -ErrorAction SilentlyContinue
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "gradlew.bat runServer > $log 2>&1" -WorkingDirectory $root -WindowStyle Hidden | Out-Null
    if (Wait-Ready) { Log "server ready"; return $true }
    return $false
}

function Wait-Lobby {
    for ($i = 0; $i -lt 90; $i++) {
        $st = (& $py -I -u -X utf8 tools/rcon.py "cww state" 2>$null) -join "`n"
        if ($st -match "phase=LOBBY" -and $st -match "teams=0" -and $st -match "queue=0") { return $true }
        if ($st -notmatch "phase=LOBBY") {
            & $py -I -u -X utf8 tools/rcon.py "cww abort" 2>$null | Out-Null
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Ensure-Server {
    if (netstat -ano | Select-String ":25576 .*LISTENING") { return $true }
    Log "server not running; starting"
    return (Start-Server)
}

if (-not (Ensure-Server)) { Log "ERROR: 无法启动服务端"; exit 1 }

for ($i = 1; $i -le 3; $i++) {
    Log "===== DRILL $i BEFORE ====="
    if (Wait-Lobby) { Log "clean lobby" } else { Log "WARN: lobby not clean" }

    & $py -I -u -X utf8 tools/restart_probe.py before 2>&1 |
        Tee-Object -FilePath $drillLog -Append | Out-Host
    Log "before rc=$LASTEXITCODE"
    if (-not (Wait-PortDown)) { Log "WARN: 25576 仍在监听" }

    Log "===== DRILL $i RESTART ====="
    if (-not (Start-Server)) { Log "ERROR: server not ready" }

    Log "===== DRILL $i AFTER ====="
    & $py -I -u -X utf8 tools/restart_probe.py after 2>&1 |
        Tee-Object -FilePath $drillLog -Append | Out-Host
    Log "after rc=$LASTEXITCODE"
}

Log "===== ALL DRILLS DONE ====="
