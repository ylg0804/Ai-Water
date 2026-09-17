@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Please install Node.js 22.12 or later, then try again.
  pause
  exit /b 1
)
if not exist "node_modules\vite\bin\vite.js" (
  call npm.cmd ci
  if errorlevel 1 (
    pause
    exit /b 1
  )
)
echo Frontend: http://127.0.0.1:5173
echo Start the Java backend first: http://127.0.0.1:8080
echo Sign in with the backend administrator account.
echo Keep this window open. Press Ctrl+C to stop.
call npm.cmd run dev -- --open
pause
