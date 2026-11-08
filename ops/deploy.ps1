<#
.SYNOPSIS
  构建模组并部署到服务端 mods/ 目录。

.DESCRIPTION
  默认先 `gradlew build`（跳过单测，部署前请自己在 CI/本地跑过一次 `test`），
  再把 build/libs 里最新的 nbw-*.jar 覆盖到目标 mods/。

.PARAMETER ModsDir
  服务端 mods/ 目录（部署目标）。

.PARAMETER SkipBuild
  跳过 gradle build，直接拷贝现有 jar。

.EXAMPLE
  .\ops\deploy.ps1 -ModsDir "C:\MinecraftServer\mods"
#>
param(
    [Parameter(Mandatory = $true)][string]$ModsDir,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path $ModsDir)) {
    throw "mods 目录不存在：$ModsDir"
}

if (-not $SkipBuild) {
    Write-Host "[deploy] gradlew build（跳过单测）"
    Push-Location $ProjectRoot
    try {
        & "$ProjectRoot\gradlew.bat" build -x test
        if ($LASTEXITCODE -ne 0) {
            throw "gradlew build 失败（exit $LASTEXITCODE）"
        }
    } finally {
        Pop-Location
    }
}

$jar = Get-ChildItem (Join-Path $ProjectRoot "build\libs") -Filter "nbw-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "build/libs 里没有 nbw-*.jar"
}

$dest = Join-Path $ModsDir $jar.Name
Copy-Item $jar.FullName $dest -Force
Write-Host "[deploy] 已部署：$dest（$([Math]::Round($jar.Length / 1KB, 1)) KB）"
