#!/usr/bin/env bash
# =============================================================================
# _lib.sh —— 所有脚本共用的函数库
# =============================================================================
# 由其他脚本 source，不单独执行。
# =============================================================================
set -uo pipefail

_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$_LIB_DIR/.." && pwd)"
ENV_FILE="${FOOTPRED_ENV_FILE:-$ROOT_DIR/config/footpred.env}"

# ---------------------------------------------------------------------------
# 环境变量兜底
# ---------------------------------------------------------------------------
# 以非交互方式执行（cron、CI、bash script.sh）时不会加载 ~/.bashrc，
# JAVA_HOME 与 hadoop 可能不在 PATH 里，导致 hdfs dfs -test 静默失败、
# 所有路径被误判为「缺失」。这里补一次，保证判断结果只取决于真实状态。
if [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ]; then
  case ":$PATH:" in *":$JAVA_HOME/bin:"*) ;; *) export PATH="$JAVA_HOME/bin:$PATH" ;; esac
fi
for _h in "${HADOOP_HOME:-}" /training/hadoop-3.3.6 /opt/module/hadoop-3.3.6; do
  if [ -n "$_h" ] && [ -x "$_h/bin/hdfs" ]; then
    case ":$PATH:" in *":$_h/bin:"*) ;; *) export PATH="$_h/bin:$PATH" ;; esac
    export HADOOP_HOME="$_h"
    if [ -z "${HADOOP_CONF_DIR:-}" ] && [ -d "$_h/etc/hadoop" ]; then
      export HADOOP_CONF_DIR="$_h/etc/hadoop"
    fi
    break
  fi
done
unset _h

# ---------------------------------------------------------------------------
# 加载配置
# ---------------------------------------------------------------------------
if [[ ! -f "$ENV_FILE" ]]; then
  printf '\033[31m缺少配置文件：%s\033[0m\n' "$ENV_FILE" >&2
  printf '  先执行：cp config/footpred.env.example config/footpred.env\n' >&2
  printf '  或执行：bash scripts/00-detect-env.sh --write\n' >&2
  exit 2
fi
# shellcheck disable=SC1090
# 调用方显式传入的 RUN_ID 必须优先于配置文件里的空值，
# 否则 `RUN_ID=xxx bash script.sh` 会被 env 文件里的 `RUN_ID=` 覆盖掉。
_CLI_RUN_ID="${RUN_ID:-}"
set -a; source "$ENV_FILE"; set +a
if [[ -n "$_CLI_RUN_ID" ]]; then
    RUN_ID="$_CLI_RUN_ID"
fi
unset _CLI_RUN_ID

# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
err()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "缺少命令：$1"; return 1; }
}
require_file() {
  [[ -f "$1" ]] || { err "缺少文件：$1"; return 1; }
}
require_dir() {
  [[ -d "$1" ]] || { err "缺少目录：$1"; return 1; }
}
require_var() {
  local name="$1"
  [[ -n "${!name:-}" ]] || { err "配置项未设置或为空：$name（检查 $ENV_FILE）"; return 1; }
}

# ---------------------------------------------------------------------------
# HDFS
# ---------------------------------------------------------------------------
hdfs_exists() { hdfs dfs -test -e "$1" 2>/dev/null; }

# 关键安全约束：绝不允许覆盖已有结果目录
require_new_hdfs_output() {
  local path="$1"
  if hdfs_exists "$path"; then
    err "拒绝覆盖已存在的 HDFS 目录：$path"
    err "换一个 RUN_ID，或手动确认后自行删除"
    return 3
  fi
}

# ---------------------------------------------------------------------------
# 路径
# ---------------------------------------------------------------------------
# HDFS 根目录：交接包小写，搭档包大写，两者不能互换
handoff_path() { printf '%s' "${FOOTPRED_HDFS_ROOT%/}$1"; }
partner_path() { printf '%s' "${FOOTPRED_HDFS_ROOT_PARTNER%/}$1"; }

new_run_id() { date '+%Y%m%d-%H%M%S'; }

# 取本次 run-id：配置里填了就用，没填就生成
resolve_run_id() {
  if [[ -n "${RUN_ID:-}" ]]; then
    printf '%s' "$RUN_ID"
  else
    new_run_id
  fi
}

# ---------------------------------------------------------------------------
# 作业执行
# ---------------------------------------------------------------------------
# 用法：run_hadoop "描述" <jar> <参数...>
# 记录耗时，失败立即返回非零
run_hadoop() {
  local label="$1"; shift
  local started ended rc
  started=$(date +%s)
  log "START $label"
  "$@"
  rc=$?
  ended=$(date +%s)
  if [[ $rc -eq 0 ]]; then
    log "DONE  $label  $((ended-started))s"
  else
    err "FAIL  $label  exit=$rc  $((ended-started))s"
  fi
  return $rc
}

# ---------------------------------------------------------------------------
# 后端
# ---------------------------------------------------------------------------
backend_pid() {
  pgrep -f "footpred-backend.*\.jar" 2>/dev/null | head -1
}

backend_running() {
  [[ -n "$(backend_pid)" ]]
}

backend_health() {
  curl -sf -m 3 "http://127.0.0.1:${BACKEND_PORT}/api/catalog/jobs" >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# 导出结果（让后端能读本地文件而非 HDFS）
# ---------------------------------------------------------------------------
# 把 HDFS 上的 part-* 合并成一个 tsv，写到 export 目录
export_result_dir() {
  local src="$1" dst="$2" header="${3:-}"
  mkdir -p "$(dirname "$dst")"
  if [[ -n "$header" ]]; then
    printf '%s\n' "$header" > "$dst"
  else
    : > "$dst"
  fi
  if hdfs_exists "$src"; then
    hdfs dfs -cat "$src"/part-* >> "$dst" 2>/dev/null
    ok "导出 $(basename "$dst")  ($(wc -l < "$dst") 行)"
  else
    warn "源不存在，跳过：$src"
    return 1
  fi
}
