@echo off
REM Wrapper: bypass PowerShell execution policy and call deploy.ps1
REM Usage: deploy.bat -ModsDir "C:\MinecraftServer\mods" [-SkipBuild]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
