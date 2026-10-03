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
rem         scripts\dev.cmd db  = keep data in DynamoDB Local (or double-click dev-db.cmd)
rem  To stop: close this window.
rem ======================================================================

setlocal
cd /d "%~dp0.."

rem "db" as the first argument: keep data in DynamoDB Local (profile "local")
set PROFILES=demo
if /i "%~1"=="db" set PROFILES=local,demo

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
curl.exe -fs -o nul http://localhost:8080/applications
if not errorlevel 1 (
  echo Stopping the backend left over from the previous run...
  call gradlew.bat --stop >nul 2>nul
  timeout /t 3 /nobreak >nul
)

rem --- [3a] (db only) Start DynamoDB Local on port 8000 (log: dynamodb.log) ---
if /i not "%~1"=="db" goto :start_backend
echo Starting DynamoDB Local. Data is kept in .local\dynamodb ...
if exist dynamodb.exited del dynamodb.exited 2>nul
start "" /b cmd /c "call gradlew.bat :backend:infra:runDynamoDbLocal --console=plain > dynamodb.log 2>&1 & echo exited> dynamodb.exited"
set /a dbtries=0
:wait_db
curl.exe -s -o nul http://localhost:8000
if not errorlevel 1 goto :start_backend
if exist dynamodb.exited (
  echo [ERROR] DynamoDB Local failed to start. See dynamodb.log
  goto :error
)
set /a dbtries+=1
if %dbtries% geq 600 (
  echo [ERROR] DynamoDB Local did not start within 10 minutes. See dynamodb.log
  goto :error
)
timeout /t 1 /nobreak >nul
goto :wait_db

:start_backend
rem --- [3] Start the backend in the background (log: backend.log) ---
echo Starting the backend. The first run can take several minutes...
if exist backend.log del backend.log 2>nul
if exist backend.exited del backend.exited 2>nul
start "" /b cmd /c "call gradlew.bat :backend:app:bootRun --args=--spring.profiles.active=%PROFILES% --console=plain > backend.log 2>&1 & echo exited> backend.exited"

rem --- [4] Wait until the backend answers (max 10 minutes) ---
set /a tries=0
:wait
curl.exe -fs -o nul http://localhost:8080/applications
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
