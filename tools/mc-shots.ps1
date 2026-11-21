# MC client capture + input helpers (ASCII-only on purpose).
#
# 这份是**入库**的正式版本（`run/` 被 .gitignore 排除，所以早期放在 run/_shots.ps1 的那份
# 别人 clone 下来根本拿不到）。用法：
#
#   iex (Get-Content ".\tools\mc-shots.ps1" -Raw -Encoding utf8)
#   Start-McClient                       # 起客户端并等到进服
#   Save-McShot "<绝对路径>.png"          # 摆窗口 + 截图 + 还原 DSH
#   Stop-McClient                        # 只杀窗口所属 PID
#
# Recipe (learned the hard way on 2026-11-21):
#   1. minimize the DSH window, otherwise it covers MC in the screen capture
#   2. restore the MC window if it is minimized (its rect is a -21333 sentinel)
#   3. CopyFromScreen over MC's *physical* window rect (needs SetProcessDPIAware)
#   4. restore DSH
#   5. never match windows/processes by "*Minecraft*" — someone else's Forge
#      client may be running on the same machine
#
# 依赖：tools/mc-window.ps1（窗口查找/矩形/键鼠注入）。下面会自动 load 它。
param()

$__mcWindow = Join-Path $PSScriptRoot "mc-window.ps1"
if (Test-Path $__mcWindow) {
    # 必须带 -Encoding utf8 再 iex：PS 5.1 默认按 ANSI 读 .ps1，
    # UTF-8 的多字节序列被错解时可能吞掉换行，把下一行并进注释里（踩过）。
    iex (Get-Content $__mcWindow -Raw -Encoding utf8)
} else {
    Write-Output "缺少 tools/mc-window.ps1（窗口工具），截图与起客户端都不可用"
}

# [removed] McWin class lives in tools/mc-window.ps1 (loaded above; a second
# Add-Type of the same class name would fail).

$script:DshTitle = "DeepSeek Harness"
# ★ 不能只匹配 "Minecraft"：这台机器上可能同时开着**别人的** Minecraft
#   （实测出现过 `Minecraft* Forge 1.20.1 - 多人游戏（第三方服务器）`）。
#   模糊匹配会同时导致两个事故：截图截到别人的游戏、Stop-McClient 直接把别人的游戏杀掉。
#   我们的 dev 客户端标题是 `Minecraft* 1.21.1 - …`，用版本号收紧才是安全的。
$script:McTitle = "Minecraft* 1.21.1"
$script:McTitleFallback = "Minecraft"
$script:McShotDir = $null

function Find-Mc { [McWin]::Find($script:McTitle) }
function Find-Dsh { [McWin]::Find($script:DshTitle) }

# 窗口矩形健全性：最小化的窗口会返回 -21333 这种哨兵坐标，
# 拿它去 CopyFromScreen 会截出一张 158x26 的垃圾图（实测过）。
function Test-McRect($r) {
    if ($null -eq $r -or $r.Count -lt 4) { return $false }
    if ($r[2] -lt 320 -or $r[3] -lt 240) { return $false }
    if ($r[0] -lt -1000 -or $r[1] -lt -1000) { return $false }
    return $true
}

function Post-Key($h, [int]$vk) { [McWin]::Key($h, [uint32]$vk) }
function Post-Char($h, [char]$c) { [McWin]::Char($h, $c) }
function Post-MouseLeft($h, [int]$x, [int]$y) { [McWin]::Mouse($h, "left", $x, $y) }
function Post-MouseRight($h, [int]$x, [int]$y) { [McWin]::Mouse($h, "right", $x, $y) }

function Show-Mc {
    # DPI awareness first: without it GetWindowRect returns logical (scaled) pixels while
    # CopyFromScreen returns physical pixels, so every capture is cropped/skewed.
    # That is exactly why the first screenshots showed a 1280x720 slice of a 1707x1067 window.
    [void][McWin]::SetProcessDPIAware()
    $mc = [McWin]::Find($script:McTitle)
    if ($mc -eq [IntPtr]::Zero) {
        Write-Output "没找到 dev 客户端窗口（标题需含 '$($script:McTitle)'）—— 先 Start-McClient"
        Write-Output ("当前可见窗口：" + ([McWin]::Titles -join " | "))
        return [IntPtr]::Zero
    }
    # 最小化的窗口会返回 -21333 这类哨兵坐标；先还原再取矩形，否则截出来是垃圾图
    if (-not (Test-McRect ([McWin]::Rect($mc)))) {
        [McWin]::ShowWindow($mc, 9) | Out-Null      # SW_RESTORE
        Start-Sleep -Milliseconds 500
    }
    $dsh = [McWin]::Find($script:DshTitle)
    if ($dsh -ne [IntPtr]::Zero) { [McWin]::ShowWindow($dsh, 6) | Out-Null }   # SW_MINIMIZE
    Start-Sleep -Milliseconds 400
    [McWin]::SetForegroundWindow($mc) | Out-Null
    Start-Sleep -Milliseconds 500
    return $mc
}

function Restore-Dsh {
    $dsh = [McWin]::Find($script:DshTitle)
    if ($dsh -ne [IntPtr]::Zero) { [McWin]::ShowWindow($dsh, 9) | Out-Null }   # SW_RESTORE
}

function Capture-Mc([string]$path, $mc = $null) {
    if ($null -eq $mc -or $mc -eq [IntPtr]::Zero) { $mc = Show-Mc }
    if ($mc -eq [IntPtr]::Zero) { return $false }
    Add-Type -AssemblyName System.Drawing
    $r = [McWin]::Rect($mc)
    if (-not (Test-McRect $r)) {
        Write-Output ("窗口矩形不可用（{0}），跳过截图 —— 先确认客户端已启动且没被最小化" -f ($r -join ","))
        return $false
    }
    $bmp = New-Object System.Drawing.Bitmap($r[2], $r[3])
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r[0], $r[1], 0, 0, $bmp.Size)
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Output ("shot: {0} ({1}x{2})" -f $path, $r[2], $r[3])
    return $true
}

# ---------------------------------------------------------------------------
# 客户端自动启动 / 自动进服
# ---------------------------------------------------------------------------
#
# 为什么要写成函数而不是直接 `gradlew runClient`：
#   1. `pauseOnLostFocus` 必须是 false —— 截图时焦点会离开 MC，否则它会自己弹暂停菜单；
#   2. `--quickPlayMultiplayer 127.0.0.1:25565` 让它启动后直接进服，不用点菜单；
#   3. runClient 的 gameDir 是 `run-client\`（不是 `run\`），options.txt 在那里；
#   4. 启动是异步的：要等窗口出现 + 服务端 `list` 里出现玩家名，才算"可以开始测"。
#
# 用法：
#   iex (Get-Content ".\run\_shots.ps1" -Raw -Encoding utf8)
#   Start-McClient                      # 启动并等到进服（最多等 180s）
#   Start-McClient -Fresh               # 先杀掉已有客户端再启动
#   Wait-McJoined "Player627" 120       # 只等服务端 list 里出现该名字

# 这个文件是 dot-source / iex 进来的，$PSCommandPath 在 iex 下是空的，
# 所以仓库根优先按脚本路径推，推不出来就退到当前目录（调用前请先 cd 到仓库根）。
$script:RepoRoot = if ($PSCommandPath) {
    Split-Path -Parent (Split-Path -Parent $PSCommandPath)
} else {
    (Get-Location).Path
}

function Set-McOptions {
    # 关掉"失焦即暂停"，并把 guiScale 固定成 3（截图里字才够大）
    param([string]$GameDir = "$($script:RepoRoot)\run-client")
    $opts = Join-Path $GameDir "options.txt"
    if (-not (Test-Path $opts)) { return $false }
    $text = [System.IO.File]::ReadAllText($opts)
    $text = $text -replace "pauseOnLostFocus:true", "pauseOnLostFocus:false"
    [System.IO.File]::WriteAllText($opts, $text, (New-Object System.Text.UTF8Encoding($false)))
    return $true
}

function Stop-McClient {
    # 只按"我们那个窗口所属的 PID"杀，不按进程名/内存筛。
    # 教训：这台机器上可能同时开着别人的 Minecraft（实测出现过 Forge 1.20.1），
    # `Get-Process java | ? MainWindowTitle -like '*Minecraft*' | Stop-Process` 会把手游一起杀掉。
    $h = [McWin]::Find($script:McTitle)
    if ($h -eq [IntPtr]::Zero) {
        Write-Output "没有找到 dev 客户端窗口（$($script:McTitle)），不杀任何进程"
        return 0
    }
    $procId = [McWin]::Pid($h)
    if ($procId -le 0) {
        Write-Output "取不到窗口 PID，放弃（避免误杀）"
        return 0
    }
    try {
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Output "已关闭 dev 客户端 pid=$procId"
        Start-Sleep -Seconds 3
        return 1
    } catch {
        Write-Output "关闭失败：$($_.Exception.Message)"
        return 0
    }
}

function Start-McClient {
    param(
        [switch]$Fresh,
        [string]$Address = "127.0.0.1:25565",
        [int]$TimeoutSec = 180
    )
    if ($Fresh) { [void](Stop-McClient) }
    [void](Set-McOptions)
    $log = Join-Path $script:RepoRoot "run\client-console.log"
    # 注意别用 $args 这个名字：它是 PowerShell 的自动变量
    $gradleArgs = 'runClient --offline --console=plain --args="--quickPlayMultiplayer ' + $Address + '"'
    Start-Process -FilePath "cmd.exe" `
        -ArgumentList '/c', ("gradlew.bat " + $gradleArgs + " > run\client-console.log 2>&1") `
        -WorkingDirectory $script:RepoRoot -WindowStyle Hidden | Out-Null
    Write-Output "客户端启动中（日志：$log）"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $h = [McWin]::Find($script:McTitle)
        if ($h -ne [IntPtr]::Zero) {
            Write-Output "窗口已出现，等待进服…"
            if (Wait-McJoined $null 90) { return $true }
        }
    }
    Write-Output "启动超时（$TimeoutSec s）"
    return $false
}

function Wait-McJoined {
    param([string]$Name = $null, [int]$TimeoutSec = 120)
    # 从服务端 `list` 看有几个人在线：>0 就算进服（不知道名字时够用）
    $env:RCON_PORT = if ($env:RCON_PORT) { $env:RCON_PORT } else { "25576" }
    $py = Join-Path $script:RepoRoot "run\py-embed\python.exe"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $out = & $py -I -X utf8 (Join-Path $script:RepoRoot "tools\rcon.py") "list" 2>&1
        $line = ($out | Where-Object { $_ -match "players online" }) -join " "
        if ($line -match "There are (\d+) of") {
            $n = [int]$Matches[1]
            if ($Name) {
                if ($line -match [regex]::Escape($Name)) { Write-Output "已进服：$Name"; return $true }
            } elseif ($n -gt 0) {
                Write-Output ("已进服（在线 {0} 人）：{1}" -f $n, $line.Trim())
                return $true
            }
        }
    }
    Write-Output "等待进服超时"
    return $false
}

# ---------------------------------------------------------------------------
# 一步到位：摆好窗口 → 截图 → 还原 DSH
# ---------------------------------------------------------------------------
function Save-McShot([string]$path) {
    $mc = Show-Mc
    if ($mc -eq [IntPtr]::Zero) { return $false }
    Start-Sleep -Milliseconds 1200
    $ok = Capture-Mc $path $mc
    Restore-Dsh
    return $ok
}
