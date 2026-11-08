<#
.SYNOPSIS
  备份 Minecraft 服务端世界（world/ + config/），并轮换旧备份。

.DESCRIPTION
  04 §4 运维基线要求"每 15min 快照留 24h"。这条脚本可以被计划任务每 15 分钟调一次，
  也可以手动跑。默认保留最近 24 个快照，旧的自动删。

  先 `/save-all flush` 再压，能得到一致的文件；本脚本不代发 RCON（避免依赖凭据），
  请在备份前用计划任务或开服脚本先 save。

.PARAMETER ServerDir
  服务端根目录（含 world/ 与 config/）。

.PARAMETER BackupDir
  备份输出目录（默认 <ServerDir>/backups）。

.PARAMETER Keep
  保留最近多少个快照（默认 24）。

.EXAMPLE
  .\ops\backup.ps1 -ServerDir "C:\MinecraftServer"
#>
param(
    [Parameter(Mandatory = $true)][string]$ServerDir,
    [string]$BackupDir = "",
    [int]$Keep = 24
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ServerDir)) {
    throw "服务端目录不存在：$ServerDir"
}
if ([string]::IsNullOrWhiteSpace($BackupDir)) {
    $BackupDir = Join-Path $ServerDir "backups"
}
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$zip = Join-Path $BackupDir "world_$stamp.zip"

$targets = @()
foreach ($name in @("world", "config")) {
    $p = Join-Path $ServerDir $name
    if (Test-Path $p) {
        $targets += $p
    }
}
if ($targets.Count -eq 0) {
    throw "没找到 world/ 或 config/：$ServerDir"
}

Write-Host "[backup] 压缩 $($targets -join ', ') -> $zip"
Compress-Archive -Path $targets -DestinationPath $zip -CompressionLevel Fastest -Force

$old = Get-ChildItem $BackupDir -Filter "world_*.zip" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -Skip $Keep
foreach ($f in $old) {
    Write-Host "[backup] 删除旧快照 $($f.Name)"
    Remove-Item $f.FullName -Force
}

$count = (Get-ChildItem $BackupDir -Filter "world_*.zip").Count
Write-Host "[backup] 完成：$zip（当前共 $count 个快照，保留 $Keep 个）"
