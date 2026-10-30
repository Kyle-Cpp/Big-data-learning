#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
APP_JAR="backend/target/footpred-backend-1.0.0.jar"
[[ -f "$APP_JAR" ]] || { echo "Build first: mvn -f backend/pom.xml package"; exit 1; }
command -v java >/dev/null || { echo "Java not found; use JDK 11 for the verified build."; exit 1; }
APP_ARGS=()
if [[ -f application-local.properties ]]; then
  APP_ARGS+=(--spring.config.additional-location=file:./application-local.properties)
fi
echo "Open http://127.0.0.1:${PORT:-8080}/?mode=live"
exec java -jar "$APP_JAR" "${APP_ARGS[@]}" --server.port="${PORT:-8080}" "$@"
