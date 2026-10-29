#!/usr/bin/env bash
# =============================================================================
# 12-status.sh —— 一键查看整个项目的运行状态
# =============================================================================
# 排查"为什么打不开"时先跑这个。
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

IP="$(hostname -I 2>/dev/null | awk '{print $1}')"

printf '\n\033[1m FootPred 运行状态 \033[0m\n'
printf ' 主机名  %s\n' "$(hostname)"
printf ' IP      %s\n' "$IP"
printf ' 配置    %s\n' "$ENV_FILE"
printf ' 时间    %s\n' "$(date '+%F %T')"

# ---------------------------------------------------------------------------
step "基础服务"
# ---------------------------------------------------------------------------
check_daemon() {
  local label="$1" proc="$2"
  if pgrep -f "$proc" >/dev/null 2>&1; then
    ok "$label 运行中（PID $(pgrep -f "$proc" | head -1)）"
  else
    warn "$label 未运行"
  fi
}
check_daemon "HDFS NameNode" "NameNode"
check_daemon "YARN ResourceManager" "ResourceManager"
check_daemon "HBase Master" "HMaster"
if systemctl is-active --quiet mysqld 2>/dev/null || systemctl is-active --quiet mysql 2>/dev/null; then
  ok "MySQL 运行中"
  if [[ "${FOOTPRED_MYSQL_ENABLED:-0}" == "1" ]]; then
    if command -v mysql >/dev/null 2>&1; then
      N=$(mysql -h "${MYSQL_HOST:-127.0.0.1}" -P "${MYSQL_PORT:-3306}" \
          -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" -N -B \
          -e "SELECT COUNT(*) FROM job_instance;" 2>/dev/null || echo "")
      if [[ -n "$N" ]]; then
        ok "元数据库可读  job_instance $N 行"
      else
        warn "元数据库读不到，运行 scripts/21-init-mysql.sh"
      fi
    fi
  else
    warn "MySQL 已启用但 FOOTPRED_MYSQL_ENABLED=0，后端不会连接"
  fi
else
  warn "MySQL 未运行"
fi

# ---------------------------------------------------------------------------
step "HDFS"
# ---------------------------------------------------------------------------
if command -v hdfs >/dev/null 2>&1 && hdfs dfsadmin -report >/dev/null 2>&1; then
  ok "NameNode 可访问"
  for p in "$FOOTPRED_HDFS_ROOT" "$FOOTPRED_HDFS_ROOT_PARTNER"; do
    if hdfs_exists "$p"; then
      n=$(hdfs dfs -ls "$p" 2>/dev/null | wc -l)
      ok "$p 存在（$(($n-1)) 个条目）"
    else
      warn "$p 不存在"
    fi
  done
else
  err "HDFS 不可访问"
fi

# ---------------------------------------------------------------------------
step "关键路径"
# ---------------------------------------------------------------------------
check_hdfs_path() {
  local label="$1" path="$2"
  if hdfs_exists "$path"; then ok "$label"; else warn "$label 缺失：$path"; fi
}
check_hdfs_path "五大联赛输入" "$DATA_FIVE_LEAGUES_HDFS"
check_hdfs_path "国家队输入" "$DATA_INTERNATIONAL_HDFS"
check_hdfs_path "模块二准备数据" "$PREPARED_MODULE2"
check_hdfs_path "模块三准备数据" "$PREPARED_MODULE3"
check_hdfs_path "俱乐部模型" "$MODEL_MODULE2_CLUB"
check_hdfs_path "国家队模型" "$MODEL_MODULE2_INTL"
check_hdfs_path "LR-xG 模型" "$MODEL_MODULE3"

# ---------------------------------------------------------------------------
step "后端"
# ---------------------------------------------------------------------------
if backend_running; then
  ok "进程运行中（PID $(backend_pid)）"
  if backend_health; then
    ok "健康检查通过"
    echo
    echo "  本机： http://127.0.0.1:${BACKEND_PORT}/api/catalog/jobs"
    echo "  主机： http://${IP}:${BACKEND_PORT}/api/catalog/jobs"
  else
    err "进程在但不响应，查看日志："
    tail -5 "$ROOT_DIR"/logs/backend-*.log 2>/dev/null | sed 's/^/    /'
  fi
else
  warn "未运行，启动： bash scripts/10-start-backend.sh --daemon"
fi

# ---------------------------------------------------------------------------
step "结果文件"
# ---------------------------------------------------------------------------
TOTAL=$(find "$FOOTPRED_LOCAL_ROOT" -name '*.tsv' 2>/dev/null | wc -l)
if [[ "$TOTAL" -gt 0 ]]; then
  ok "已导出 $TOTAL 个 tsv"
  find "$FOOTPRED_LOCAL_ROOT" -name '*.tsv' 2>/dev/null | head -10 | while read -r f; do
    printf '      %-52s %6s 行\n' "$(basename "$f")" "$(wc -l < "$f")"
  done
else
  warn "没有导出文件，运行： bash scripts/20-export-results.sh"
fi

# ---------------------------------------------------------------------------
step "YARN 最近作业"
# ---------------------------------------------------------------------------
if command -v yarn >/dev/null 2>&1; then
  yarn application -list 2>/dev/null | head -6 || warn "无法列出作业"
fi

printf '\n'
