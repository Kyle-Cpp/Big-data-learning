@echo off
setlocal
cd /d "%~dp0"
set "APP_JAR=backend\target\footpred-backend-1.0.0.jar"
if not exist "%APP_JAR%" (
  echo [ERROR] Build first: mvn -f backend/pom.xml package
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java not found. Use JDK 11 for the verified build.
  exit /b 1
)
echo Open http://127.0.0.1:8080/?mode=live
if exist "application-local.properties" (
  java -jar "%APP_JAR%" --spring.config.additional-location=file:./application-local.properties %*
) else (
  java -jar "%APP_JAR%" %*
)
exit /b %errorlevel%
