@echo off
REM FootPred 一键启动（Windows）
REM 在项目根目录双击或在终端执行：start.bat

setlocal
cd /d "%~dp0"

if not exist "target\footpred-backend-1.0.0.jar" (
    echo [错误] 找不到 target\footpred-backend-1.0.0.jar
    echo        请确认已解压完整，或在 backend 目录执行 mvn clean package
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 java，请先安装 Java 8 或 Java 17
    pause
    exit /b 1
)

echo ============================================
echo  FootPred 足球大数据分析平台
echo ============================================
echo.
echo  启动后打开： http://127.0.0.1:8080
echo  停止服务：   Ctrl + C
echo.
echo  默认展示示例数据，右上角可切换「真实接口」
echo ============================================
echo.

java -jar target\footpred-backend-1.0.0.jar --server.port=8080

if errorlevel 1 (
    echo.
    echo [提示] 若提示端口被占用，可改用：
    echo        java -jar target\footpred-backend-1.0.0.jar --server.port=8081
    pause
)
endlocal
