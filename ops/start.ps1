<#
.SYNOPSIS
  启动 Minecraft 服务端，崩溃后自动重启（带延迟）。

.DESCRIPTION
  用 java 拉起 server.jar，日志追加到 <ServerDir>/logs/console-<日期>.log。
  进程退出后（无论正常/异常）等待 RestartDelaySec 秒再拉起；Ctrl+C 只会停掉当前 java，
  脚本本身收到 Ctrl+C 后不再重启。

.PARAMETER ServerDir
  服务端根目录（含 server.jar）。

.PARAMETER Java
  java 可执行路径（默认 PATH 里的 java）。

.PARAMETER ServerJar
  服务端 jar 文件名（默认 server.jar）。

.PARAMETER Xmx
  最大堆（默认 4G）。

.PARAMETER Xms
  初始堆（默认 2G）。

.EXAMPLE
  .\ops\start.ps1 -ServerDir "C:\MinecraftServer"
#>
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [string]$Java = "java",
    [string]$ServerJar = "server.jar",
    [string]$Xmx = "4G",
    [string]$Xms = "2G",
    [int]$RestartDelaySec = 10
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path (Join-Path $ServerDir $ServerJar))) {
    throw "找不到服务端 jar：$(Join-Path $ServerDir $ServerJar)"
}
New-Item -ItemType Directory -Force -Path (Join-Path $ServerDir "logs") | Out-Null

$stopping = $false
$handler = {
    $script:stopping = $true
    Write-Host "[start] 收到中断，停止重启循环（当前 java 进程请自行 Ctrl+C 或关窗）"
}
try { [Console]::TreatControlCAsInput = $false } catch { }

while (-not $stopping) {
    $stamp = Get-Date -Format "yyyyMMdd"
    $log = Join-Path $ServerDir "logs\console-$stamp.log"
    $args = @("-Xms$Xms", "-Xmx$Xmx", "-jar", $ServerJar, "nogui")
    Write-Host "[start] $Java $($args -join ' ')（日志追加到 $log）"
    & $Java @args *>&1 | Tee-Object -FilePath $log -Append
    $code = $LASTEXITCODE
    if ($stopping) {
        Write-Host "[start] 已停止（exit $code）"
        break
    }
    Write-Host "[start] 服务端退出（exit $code），$RestartDelaySec 秒后重启……"
    Start-Sleep -Seconds $RestartDelaySec
}
