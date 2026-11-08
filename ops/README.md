# ops · 部署与运维脚本

> 对应 `docs/dev/04-依赖与运维.md` §4 运维基线。三个脚本互不依赖，按需组合。

## 直接用 `.bat`（推荐，绕过执行策略）

Windows 默认禁止运行 `.ps1`。每个 `.ps1` 都配了同名 `.bat` 包装（`-ExecutionPolicy Bypass`），
**优先用 `.bat`**：

```powershell
.\ops\deploy.bat -ModsDir "C:\MinecraftServer\mods"
.\ops\start.bat  -ServerDir "C:\MinecraftServer"
.\ops\backup.bat -ServerDir "C:\MinecraftServer"
```

`.ps1` 已存为 **UTF-8 with BOM**（否则 Windows PowerShell 5.1 会按 ANSI 读中文注释导致语法错）。

## 一键部署（把模组扔进服务端）

```powershell
.\ops\deploy.bat -ModsDir "C:\MinecraftServer\mods"
```

## 启动（崩溃自动重启）

```powershell
.\ops\start.bat -ServerDir "C:\MinecraftServer"
```

## 备份（世界 + 配置，轮换）

```powershell
.\ops\backup.bat -ServerDir "C:\MinecraftServer"
.\ops\backup.bat -ServerDir "C:\MinecraftServer" -Keep 48
```

## 计划任务示例（15 分钟一次）

```powershell
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File C:\path\to\ops\backup.ps1 -ServerDir C:\MinecraftServer"
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 15)
Register-ScheduledTask -TaskName "NBW-Backup" -Action $action -Trigger $trigger -RunLevel Highest
```

## 恢复演练（04 §4）

1. 对局中强杀 → 重启恢复到当前阶段。
2. **移动中的筏体强杀 → 重启后 sub-level 位置与方块实体一致**（Sable 特有风险，M0-9）。
3. 从备份还原整服：解压 `backups/world_*.zip` 回 `ServerDir`（先停服）。
