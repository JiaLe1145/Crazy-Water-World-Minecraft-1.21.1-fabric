@echo off
REM Wrapper: bypass PowerShell execution policy and call backup.ps1
REM Usage: backup.bat -ServerDir "C:\MinecraftServer" [-Keep 24]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backup.ps1" %*
