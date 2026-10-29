@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo   FootPred Football Analytics Platform
echo ============================================
echo.

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

echo   URL:    http://127.0.0.1:8080
echo   Stop:   Ctrl + C
echo.
echo   Default shows sample data.
echo   Switch to "Live" in the top-right corner for real results.
echo ============================================
echo.

java -jar target\footpred-backend-1.0.0.jar --server.port=8080

if errorlevel 1 (
    echo.
    echo [TIP] If port 8080 is busy, try:
    echo       java -jar target\footpred-backend-1.0.0.jar --server.port=8081
    pause
)
endlocal
