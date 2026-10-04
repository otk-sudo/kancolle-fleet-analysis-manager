@echo off
rem ======================================================================
rem  Windows starter for the prototype (Windows version of scripts/dev.sh).
rem
rem  IMPORTANT: keep this file ASCII only (English letters and symbols).
rem  cmd.exe reads .cmd files in the console code page (CP932 on Japanese
rem  Windows), so Japanese text here breaks the script.
rem  The Japanese explanation of every step is in scripts/README.md.
rem
rem  Usage: double-click scripts\dev.cmd in Explorer.
rem         scripts\dev.cmd db  = keep data in a SQLite file (or double-click dev-db.cmd)
rem  To stop: close this window.
rem ======================================================================

setlocal
cd /d "%~dp0.."

rem "db" as the first argument: keep data in .local\data\fleet-analysis.db (SQLite)
rem Otherwise the data is kept in memory and is reset at every start
set APP_STORAGE=memory
if /i "%~1"=="db" set APP_STORAGE=sqlite
set APP_DATA_DIR=%CD%\.local\data

rem --- [1] Check required tools (Java 21, Node.js 22, curl.exe) ---
if exist "%JAVA_HOME%\bin\java.exe" goto :check_node
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java not found. Please install Java 21 and run again.
  goto :error
)
:check_node
where npm >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js not found. Please install Node.js 22 and run again.
  goto :error
)
where curl.exe >nul 2>nul
if errorlevel 1 (
  echo [ERROR] curl.exe not found. Please run Windows Update.
  goto :error
)

rem --- [2] Stop a backend left over from the previous run ---
rem Any HTTP answer (even 403 or 404) means something is running on 8080, so no -f here
set LEFTOVER=0
curl.exe -s -o nul http://127.0.0.1:8080/applications
if not errorlevel 1 set LEFTOVER=1
if "%LEFTOVER%"=="1" (
  echo Stopping the backend left over from the previous run...
  call gradlew.bat --stop >nul 2>nul
  timeout /t 3 /nobreak >nul
)

rem --- [3] Start the backend in the background (log: backend.log) ---
echo Starting the backend. The first run can take several minutes...
rem The screen is served by Vite on port 5173, so the backend must accept requests from there
set APP_EXTRA_ORIGINS=http://localhost:5173,http://127.0.0.1:5173
if exist backend.log del backend.log 2>nul
if exist backend.exited del backend.exited 2>nul
start "" /b cmd /c "call gradlew.bat :backend:app:bootRun --args=--spring.profiles.active=demo --console=plain > backend.log 2>&1 & echo exited> backend.exited"

rem --- [4] Wait until the backend answers (max 10 minutes) ---
rem The backend listens on 127.0.0.1 only
set /a tries=0
:wait
curl.exe -fs -o nul http://127.0.0.1:8080/applications
if not errorlevel 1 goto :started
if exist backend.exited goto :backend_failed
set /a tries+=1
if %tries% geq 600 (
  echo [ERROR] The backend did not answer within 10 minutes.
  echo If the first download is just slow, run this script again.
  goto :show_log
)
timeout /t 1 /nobreak >nul
goto :wait

:started
echo Backend started. Log file: backend.log

rem --- [5] Start the frontend (Vite) ---
echo Starting the screen. Open http://localhost:5173 in your browser.
cd frontend
if not exist node_modules (
  call npm ci
  if errorlevel 1 goto :error
)
call npm run generate:api
if errorlevel 1 goto :error
call npx vite
cd ..
call gradlew.bat --stop >nul 2>nul
goto :eof

:backend_failed
echo [ERROR] The backend failed to start.
echo Common causes: Java is not version 21, or another program uses port 8080.

:show_log
rem Show the last 30 lines of backend.log so the cause is visible here.
echo ---------------- last lines of backend.log ----------------
if exist backend.log powershell -NoProfile -Command "Get-Content -Path backend.log -Tail 30"
echo -----------------------------------------------------------
echo Full log: "%CD%\backend.log"

:error
cd /d "%~dp0.."
call gradlew.bat --stop >nul 2>nul
pause
exit /b 1
