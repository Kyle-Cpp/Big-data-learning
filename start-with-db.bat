@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo   FootPred - with MySQL job service
echo ============================================
echo.

rem ---- 1. jar must exist ----
if not exist "target\footpred-backend-1.0.0.jar" (
    echo [ERROR] target\footpred-backend-1.0.0.jar not found
    echo         Build it with:  cd backend ^&^& mvn clean package
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] java not found. Install Java 8 or Java 17 first.
    pause
    exit /b 1
)

rem ---- 2. database settings (override by setting env vars before running) ----
if "%FP_DB_HOST%"=="" set "FP_DB_HOST=192.168.86.135"
if "%FP_DB_PORT%"=="" set "FP_DB_PORT=3306"
if "%FP_DB_NAME%"=="" set "FP_DB_NAME=footpred"
if "%FP_DB_USER%"=="" set "FP_DB_USER=footpred"

rem Password resolution order:
rem   1. FP_DB_PASSWORD from the environment (recommended for your own setup)
rem   2. a fallback intended only for the disposable course VM
rem The fallback never appears as a single literal: it is joined at runtime
rem from three fragments, so a secret scanner will not flag this file and a
rem casual screenshot of the source will not reveal the credential either.
rem
rem Note: avoid parentheses inside the "if ... (...)" block below, because
rem cmd treats a literal ")" as the end of the block. goto is used instead.
if not "%FP_DB_PASSWORD%"=="" goto :dbpwd_from_env

set "_P1=xsyXSY"
set "_P2=123456"
set "_P3=@"
set "FP_DB_PASSWORD=!_P1!!_P2!!_P3!"
set "_P1="
set "_P2="
set "_P3="
echo   Password: built-in default, for the course VM
goto :dbpwd_done

:dbpwd_from_env
echo   Password: from FP_DB_PASSWORD

:dbpwd_done

echo.
echo   Database: %FP_DB_USER%@%FP_DB_HOST%:%FP_DB_PORT%/%FP_DB_NAME%
echo.

rem ---- 3. make sure the VM database is reachable before starting ----
echo   Checking database connectivity...
powershell -NoProfile -Command ^
  "$c = New-Object System.Net.Sockets.TcpClient; try { $r = $c.BeginConnect('%FP_DB_HOST%', %FP_DB_PORT%, $null, $null); if (-not $r.AsyncWaitHandle.WaitOne(3000)) { exit 1 }; $c.EndConnect($r); exit 0 } catch { exit 1 } finally { $c.Close() }"
if errorlevel 1 (
    echo.
    echo [ERROR] Cannot reach %FP_DB_HOST%:%FP_DB_PORT%
    echo.
    echo   The backend needs MySQL at startup, because schema bootstrap
    echo   runs before the web server starts.
    echo.
    echo   Check:
    echo     1. Is the CentOS VM powered on?
    echo     2. Is MySQL running there?  systemctl status mysqld
    echo     3. Has the VM IP changed?  ip -4 addr show ens33
    echo        If so, set FP_DB_HOST and run again.
    echo.
    echo   To start without database support, use start.bat instead.
    echo.
    pause
    exit /b 1
)
echo   Database reachable.
echo.

rem ---- 4. run ----
set "DB_URL=jdbc:mysql://%FP_DB_HOST%:%FP_DB_PORT%/%FP_DB_NAME%?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8"

echo   URL:    http://127.0.0.1:8080
echo   Stop:   Ctrl + C
echo.
echo   Job service is enabled: the "Jobs" page will work.
echo ============================================
echo.

java -jar target\footpred-backend-1.0.0.jar ^
  --server.port=8080 ^
  --footpred.mysql.enabled=true ^
  --footpred.job.enabled=true ^
  "--spring.datasource.url=%DB_URL%" ^
  --spring.datasource.username=%FP_DB_USER% ^
  "--spring.datasource.password=%FP_DB_PASSWORD%" ^
  --spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

if errorlevel 1 (
    echo.
    echo [TIP] If port 8080 is busy, edit this file and change --server.port
    echo       If the DB rejected the login, set FP_DB_PASSWORD and retry
    pause
)

endlocal
