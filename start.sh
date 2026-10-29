#!/usr/bin/env bash
# FootPred 一键启动（Linux / macOS / Git Bash）
set -euo pipefail
cd "$(dirname "$0")"

JAR="target/footpred-backend-1.0.0.jar"
PORT="${PORT:-8080}"

[ -f "$JAR" ] || { echo "[错误] 找不到 $JAR"; exit 1; }
command -v java >/dev/null || { echo "[错误] 未找到 java，请先安装 Java 8 或 Java 17"; exit 1; }

echo "============================================"
echo " FootPred 足球大数据分析平台"
echo "============================================"
echo
echo " 启动后打开： http://127.0.0.1:${PORT}"
echo " 停止服务：   Ctrl + C"
echo

exec java -jar "$JAR" --server.port="$PORT"
