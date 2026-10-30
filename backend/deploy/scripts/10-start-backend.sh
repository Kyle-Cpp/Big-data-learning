#!/usr/bin/env bash
# =============================================================================
# 10-start-backend.sh —— 启动后端服务
# =============================================================================
# 在虚拟机里执行。启动前会检查依赖、端口和结果目录。
#
# 用法：
#   bash scripts/10-start-backend.sh            # 前台启动（Ctrl-C 停止）
#   bash scripts/10-start-backend.sh --daemon   # 后台启动，日志写 logs/
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

DAEMON=0
[[ "${1:-}" == "--daemon" ]] && DAEMON=1

step "检查配置"
require_var BACKEND_PORT     || exit 2
require_var FOOTPRED_LOCAL_ROOT || exit 2
ok "端口        $BACKEND_PORT"
ok "结果根目录  $FOOTPRED_LOCAL_ROOT"
ok "HDFS 根     $FOOTPRED_HDFS_ROOT（交接包） / $FOOTPRED_HDFS_ROOT_PARTNER（搭档包）"

step "检查后端 JAR"
BACKEND_JAR="$(find "$ROOT_DIR" -maxdepth 3 -name 'footpred-backend-*.jar' 2>/dev/null | grep -v original | head -1)"
if [[ -z "$BACKEND_JAR" ]]; then
  err "未找到 footpred-backend-*.jar"
  err "请先在主机执行 mvn package，再把 target/*.jar 拷到 $ROOT_DIR/"
  exit 2
fi
ok "JAR $BACKEND_JAR"

step "检查端口占用"
if backend_running; then
  err "后端已在运行（PID $(backend_pid)），先执行 scripts/11-stop-backend.sh"
  exit 2
fi
if ss -tln 2>/dev/null | grep -q ":${BACKEND_PORT} "; then
  err "端口 ${BACKEND_PORT} 已被其他进程占用"
  ss -tlnp 2>/dev/null | grep ":${BACKEND_PORT} " || true
  err "改 BACKEND_PORT 或停掉占用进程"
  exit 2
fi
ok "端口 ${BACKEND_PORT} 空闲"

step "检查结果目录"
if [[ ! -d "$FOOTPRED_LOCAL_ROOT" ]]; then
  warn "结果目录不存在：$FOOTPRED_LOCAL_ROOT"
  warn "服务能启动，但查询接口会返回空结果"
  warn "先运行 scripts/20-export-results.sh 导出数据"
  mkdir -p "$FOOTPRED_LOCAL_ROOT"
else
  COUNT=$(find "$FOOTPRED_LOCAL_ROOT" -name '*.tsv' -o -name 'part-*' 2>/dev/null | wc -l)
  ok "结果文件 $COUNT 个"
fi

step "检查 Java"
require_cmd java || exit 2
ok "$(java -version 2>&1 | head -1)"

# ---------------------------------------------------------------------------
# 启动
# ---------------------------------------------------------------------------
mkdir -p "$ROOT_DIR/logs"
LOG_FILE="$ROOT_DIR/logs/backend-$(date +%Y%m%d).log"

# 把配置里的键转成 Spring 能识别的形式：
# FOOTPRED_LOCAL_ROOT -> footpred.local.root（Spring 的 relaxed binding 自动处理）
# 所以直接用环境变量传，不需要额外转换。
export FOOTPRED_LOCAL_ROOT BACKEND_PORT
export FOOTPRED_HDFS_ROOT FOOTPRED_HDFS_ROOT_PARTNER
export FOOTPRED_RESULT_MODULE2_EVALUATION FOOTPRED_RESULT_MODULE2_CLUB_MODEL
export FOOTPRED_RESULT_MODULE2_INTERNATIONAL_MODEL FOOTPRED_RESULT_MODULE3_MODEL
export FOOTPRED_RESULT_SIMULATION FOOTPRED_RESULT_REAL_TABLE FOOTPRED_RESULT_TEAMS_BY_LEAGUE
export FOOTPRED_DRAW_MARGIN_CLUB FOOTPRED_DRAW_MARGIN_INTERNATIONAL
# MySQL 开关与连接串。ENABLED 为 0 时后端不注册数据源，
# 只读结果层照常工作，所以这里不需要区分两套启动命令。
export FOOTPRED_MYSQL_ENABLED="${FOOTPRED_MYSQL_ENABLED:-0}"
export SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
export SPRING_DATASOURCE_DRIVER_CLASS_NAME="${SPRING_DATASOURCE_DRIVER_CLASS_NAME:-com.mysql.cj.jdbc.Driver}"

if [[ "$FOOTPRED_MYSQL_ENABLED" == "1" ]]; then
  step "MySQL 已启用"
  ok "URL ${SPRING_DATASOURCE_URL%%\?*}"
  ok "用户 ${SPRING_DATASOURCE_USERNAME}"
fi

# 作业提交层。ENABLED=0 时不注册执行器，POST /api/jobs 返回 503。
export FOOTPRED_JOB_ENABLED="${FOOTPRED_JOB_ENABLED:-0}"
export FOOTPRED_JOB_MAX_CONCURRENT="${FOOTPRED_JOB_MAX_CONCURRENT:-1}"
export FOOTPRED_HADOOP_BIN="${FOOTPRED_HADOOP_BIN:-hadoop}"
export FOOTPRED_JAR_HANDOFF FOOTPRED_JAR_PARTNER
export FOOTPRED_JAR_HANDOFF_MODULE3 FOOTPRED_JAR_HANDOFF_MODULE5
export YARN_RM_URL="${YARN_RM_URL:-http://${YARN_RM_HOST:-127.0.0.1}:${YARN_RM_PORT:-8088}}"

if [[ "$FOOTPRED_JOB_ENABLED" == "1" ]]; then
  step "作业提交已启用"
  if [[ "$FOOTPRED_MYSQL_ENABLED" != "1" ]]; then
    err "作业提交需要 MySQL 保存状态，请同时设置 FOOTPRED_MYSQL_ENABLED=1"
    exit 2
  fi
  ok "hadoop  ${FOOTPRED_HADOOP_BIN}"
  ok "并发上限 ${FOOTPRED_JOB_MAX_CONCURRENT}"
  ok "YARN    ${YARN_RM_URL}"
  if [[ ! -x "$FOOTPRED_HADOOP_BIN" ]] && ! command -v "$FOOTPRED_HADOOP_BIN" >/dev/null 2>&1; then
    err "找不到 hadoop：$FOOTPRED_HADOOP_BIN"
    err "在 footpred.env 里填绝对路径，例如 /opt/module/hadoop-3.3.6/bin/hadoop"
    exit 2
  fi
fi

# ---------------------------------------------------------------------------
# Spring 参数
# ---------------------------------------------------------------------------
# 环境变量 FOOTPRED_MYSQL_ENABLED 不会被 Spring 的 relaxed binding 映射成
# footpred.mysql.enabled（全大写环境变量里没有点号，Boot 无法拆分前缀），
# 因此这里必须显式转成 --key=value 传给 JVM。
APP_ARGS=(
  "--server.port=${BACKEND_PORT}"
  "--server.address=${BACKEND_HOST:-0.0.0.0}"
  "--footpred.local.root=${FOOTPRED_LOCAL_ROOT}"
  "--footpred.hdfs.root=${FOOTPRED_HDFS_ROOT}"
  "--footpred.hdfs.root.partner=${FOOTPRED_HDFS_ROOT_PARTNER}"
  "--footpred.mysql.enabled=${FOOTPRED_MYSQL_ENABLED}"
  "--footpred.job.enabled=${FOOTPRED_JOB_ENABLED}"
  "--footpred.job.max-concurrent=${FOOTPRED_JOB_MAX_CONCURRENT}"
  "--footpred.hadoop.bin=${FOOTPRED_HADOOP_BIN}"
  "--footpred.yarn.rm-url=${YARN_RM_URL}"
)
[[ -n "${FOOTPRED_RESULT_MODULE2_EVALUATION:-}" ]] && APP_ARGS+=("--footpred.result.module2-evaluation=${FOOTPRED_RESULT_MODULE2_EVALUATION}")
[[ -n "${FOOTPRED_RESULT_MODULE2_CLUB_MODEL:-}" ]] && APP_ARGS+=("--footpred.result.module2-club-model=${FOOTPRED_RESULT_MODULE2_CLUB_MODEL}")
[[ -n "${FOOTPRED_RESULT_MODULE2_INTERNATIONAL_MODEL:-}" ]] && APP_ARGS+=("--footpred.result.module2-international-model=${FOOTPRED_RESULT_MODULE2_INTERNATIONAL_MODEL}")
[[ -n "${FOOTPRED_RESULT_MODULE3_MODEL:-}" ]] && APP_ARGS+=("--footpred.result.module3-model=${FOOTPRED_RESULT_MODULE3_MODEL}")
[[ -n "${FOOTPRED_RESULT_SIMULATION:-}" ]] && APP_ARGS+=("--footpred.result.simulation=${FOOTPRED_RESULT_SIMULATION}")
[[ -n "${FOOTPRED_RESULT_REAL_TABLE:-}" ]] && APP_ARGS+=("--footpred.result.real-table=${FOOTPRED_RESULT_REAL_TABLE}")
[[ -n "${FOOTPRED_RESULT_TEAMS_BY_LEAGUE:-}" ]] && APP_ARGS+=("--footpred.result.teams-by-league=${FOOTPRED_RESULT_TEAMS_BY_LEAGUE}")
[[ -n "${FOOTPRED_DRAW_MARGIN_CLUB:-}" ]] && APP_ARGS+=("--footpred.draw-margin.club=${FOOTPRED_DRAW_MARGIN_CLUB}")
[[ -n "${FOOTPRED_DRAW_MARGIN_INTERNATIONAL:-}" ]] && APP_ARGS+=("--footpred.draw-margin.international=${FOOTPRED_DRAW_MARGIN_INTERNATIONAL}")
[[ -n "${FOOTPRED_JAR_HANDOFF:-}" ]] && APP_ARGS+=("--footpred.jar.handoff=${FOOTPRED_JAR_HANDOFF}")
[[ -n "${FOOTPRED_JAR_HANDOFF_MODULE3:-}" ]] && APP_ARGS+=("--footpred.jar.handoff.module3=${FOOTPRED_JAR_HANDOFF_MODULE3}")
[[ -n "${FOOTPRED_JAR_HANDOFF_MODULE5:-}" ]] && APP_ARGS+=("--footpred.jar.handoff.module5=${FOOTPRED_JAR_HANDOFF_MODULE5}")
[[ -n "${FOOTPRED_JAR_PARTNER:-}" ]] && APP_ARGS+=("--footpred.jar.partner=${FOOTPRED_JAR_PARTNER}")

# @ConditionalOnProperty 的 havingValue 是 "true"，而运维习惯写 1/0，
# 这里做一次归一化，否则 Bean 静默不注册、接口返回 404。
bool_flag() { case "${1:-0}" in 1|true|TRUE|yes|on) echo true ;; *) echo false ;; esac; }
for i in "${!APP_ARGS[@]}"; do
  case "${APP_ARGS[$i]}" in
    --footpred.mysql.enabled=*|--footpred.job.enabled=*)
      key="${APP_ARGS[$i]%%=*}"
      val="${APP_ARGS[$i]#*=}"
      APP_ARGS[$i]="${key}=$(bool_flag "$val")"
      ;;
  esac
done

if [[ "$FOOTPRED_MYSQL_ENABLED" == "1" ]]; then
  APP_ARGS+=("--spring.datasource.url=${SPRING_DATASOURCE_URL}")
  APP_ARGS+=("--spring.datasource.username=${SPRING_DATASOURCE_USERNAME}")
  APP_ARGS+=("--spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}")
  APP_ARGS+=("--spring.datasource.driver-class-name=${SPRING_DATASOURCE_DRIVER_CLASS_NAME}")
fi

if [[ $DAEMON -eq 1 ]]; then
  nohup java -jar "$BACKEND_JAR" "${APP_ARGS[@]}" >> "$LOG_FILE" 2>&1 &
  echo $! > "$ROOT_DIR/logs/backend.pid"
  log "PID $(cat "$ROOT_DIR/logs/backend.pid")"

  # 等待就绪（最多 40 秒）
  for i in $(seq 1 40); do
    if backend_health; then
      ok "启动成功（${i} 秒）"
      IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
      echo
      echo "  访问地址： http://${IP}:${BACKEND_PORT}/api/catalog/jobs"
      echo "  本机访问： http://127.0.0.1:${BACKEND_PORT}/api/catalog/jobs"
      echo "  日志：     $LOG_FILE"
      echo "  停止：     bash scripts/11-stop-backend.sh"
      exit 0
    fi
    sleep 1
  done

  err "40 秒内未就绪，查看日志尾部："
  tail -30 "$LOG_FILE" >&2
  exit 1
else
  step "前台启动（Ctrl-C 停止）"
  IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
  echo "  访问地址： http://${IP}:${BACKEND_PORT}/api/catalog/jobs"
  echo
  exec java -jar "$BACKEND_JAR" "${APP_ARGS[@]}"
fi
