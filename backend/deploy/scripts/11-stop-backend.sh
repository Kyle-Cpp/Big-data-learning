#!/usr/bin/env bash
# =============================================================================
# 11-stop-backend.sh —— 停止后端服务
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

PID_FILE="$ROOT_DIR/logs/backend.pid"

step "查找后端进程"

PID=""
if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE" 2>/dev/null)"
  # 校验这个 PID 确实是后端，避免误杀复用 PID 的进程
  if [[ -n "$PID" ]] && ! ps -p "$PID" -o args= 2>/dev/null | grep -q "footpred-backend"; then
    warn "PID 文件中的 $PID 不是后端进程，忽略"
    PID=""
  fi
fi

if [[ -z "$PID" ]]; then
  PID="$(backend_pid)"
fi

if [[ -z "$PID" ]]; then
  warn "后端未在运行"
  rm -f "$PID_FILE"
  exit 0
fi

ok "PID $PID"

step "停止"
# 先 TERM 让 Spring 优雅关闭（释放连接池、写完日志）
kill -TERM "$PID" 2>/dev/null
for i in $(seq 1 15); do
  if ! ps -p "$PID" >/dev/null 2>&1; then
    ok "已停止（${i} 秒）"
    rm -f "$PID_FILE"
    exit 0
  fi
  sleep 1
done

warn "15 秒未退出，强制终止"
kill -9 "$PID" 2>/dev/null
sleep 1
if ps -p "$PID" >/dev/null 2>&1; then
  err "无法停止 PID $PID"
  exit 1
fi
ok "已强制停止"
rm -f "$PID_FILE"
