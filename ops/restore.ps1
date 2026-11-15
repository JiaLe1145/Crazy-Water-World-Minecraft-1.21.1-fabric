<#
.SYNOPSIS
  从 ops/backup.ps1 生成的 world_*.zip 还原服务端世界与配置。

.DESCRIPTION
  做三件事：
    1. 检查 25565 是否仍在监听（防在服运行中还原）；-Force 可跳过。
    2. 把当前 world/ 与 config/ 移到 world.pre-restore.<时间戳>/，
       解压备份到 ServerDir；解压失败自动把旧目录移回来。
    3. 成功后默认删除旧目录；-KeepOld 保留，便于对照。

  还原后必须自己启动服务端并核对：
    cww state 的 phase/match 是否回到备份时点。

.PARAMETER ServerDir
  服务端根目录（含 world/ 与 config/，以及 backups/）。

.PARAMETER Backup
  ops/backup.ps1 生成的 world_*.zip 路径。

.PARAMETER Force
  即使 25565 仍在监听也继续（危险；只给测试环境用）。

.PARAMETER KeepOld
  保留还原前的 world.pre-restore.* / config.pre-restore.*。

.EXAMPLE
  .\ops\restore.ps1 -ServerDir "C:\MinecraftServer" -Backup "C:\MinecraftServer\backups\world_20261115_081234.zip"
#>
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [Parameter(Mandatory = $true)][string]$Backup,
    [switch]$Force,
    [switch]$KeepOld
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ServerDir)) {
    throw "服务端目录不存在：$ServerDir"
}
if (-not (Test-Path $Backup)) {
    throw "备份文件不存在：$Backup"
}
if ([IO.Path]::GetExtension($Backup) -ne ".zip") {
    throw "备份必须是 .zip：$Backup"
}

$listener = $null
try {
    $listener = Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue
} catch {
    # 老系统/受限环境没有 Get-NetTCPConnection；交给调用方自己保证已停服。
}
if ($listener -and -not $Force) {
    throw "25565 仍在监听：先停服，或加 -Force（只给测试环境）"
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$old = @()
foreach ($name in @("world", "config")) {
    $p = Join-Path $ServerDir $name
    if (Test-Path $p) {
        $bak = Join-Path $ServerDir ("$name.pre-restore.$stamp")
        Move-Item $p $bak
        $old += [pscustomobject]@{ Name = $name; Path = $p; Bak = $bak }
        Write-Host "[restore] 暂存旧目录：$p -> $bak"
    }
}

try {
    Write-Host "[restore] 解压 $Backup -> $ServerDir"
    Expand-Archive -Path $Backup -DestinationPath $ServerDir -Force
} catch {
    Write-Host "[restore] 解压失败，回滚旧目录"
    foreach ($o in $old) {
        if (Test-Path $o.Path) {
            Remove-Item -Recurse -Force $o.Path
        }
        Move-Item $o.Bak $o.Path
    }
    throw
}

foreach ($o in $old) {
    if ($KeepOld) {
        Write-Host "[restore] 旧目录保留：$($o.Bak)"
    } else {
        Remove-Item -Recurse -Force $o.Bak
        Write-Host "[restore] 已删除旧目录：$($o.Bak)"
    }
}

Write-Host "[restore] 完成：$Backup"
Write-Host "[restore] 下一步：启动服务端，核对 cww state 的 phase/match 与备份时点一致"
