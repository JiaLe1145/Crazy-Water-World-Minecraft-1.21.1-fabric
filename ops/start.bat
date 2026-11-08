@echo off
REM Wrapper: bypass PowerShell execution policy and call start.ps1
REM Usage: start.bat -ServerDir "C:\MinecraftServer"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
