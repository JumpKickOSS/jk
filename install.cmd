@echo off
REM Windows entrypoint for install.ps1 — bypasses the default Restricted execution policy
REM so `install.cmd build\dist\jk.exe` works from cmd or PowerShell without a profile change.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
exit /b %ERRORLEVEL%
