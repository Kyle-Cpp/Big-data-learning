#!/usr/bin/env bash
# =============================================================================
# 22-import-results.sh —— 把导出的结果导入 MySQL
# =============================================================================
# 前提：已运行 20-export-results.sh 生成 tsv，且后端已启用 MySQL。
#
# 用法：
#   bash scripts/22-import-results.sh
#   RUN_ID=20261025-143000 bash scripts/22-import-results.sh
#   bash scripts/22-import-results.sh --dry-run    # 只列出将要导入的文件
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

RUN_ID_RESOLVED="$(resolve_run_id)"
EXPORT_ROOT="${FOOTPRED_EXPORT_ROOT:-$ROOT_DIR/export}"
RUN_DIR="$EXPORT_ROOT/runs/$RUN_ID_RESOLVED"

step "导入准备"
ok "RUN_ID $RUN_ID_RESOLVED"
ok "目录   $RUN_DIR"

if [[ ! -d "$RUN_DIR" ]]; then
  err "导出目录不存在：$RUN_DIR"
  err "先执行： RUN_ID=$RUN_ID_RESOLVED bash scripts/20-export-results.sh"
  exit 2
fi

if [[ "${FOOTPRED_MYSQL_ENABLED:-0}" != "1" ]]; then
  warn "配置里 FOOTPRED_MYSQL_ENABLED 不是 1"
  warn "后端不会注册 /api/jobs 与 /api/admin/import 接口"
  echo "     若只想先看导入内容，可加 --dry-run"
fi

# ---------------------------------------------------------------------------
# 收集待导入文件
# ---------------------------------------------------------------------------
step "扫描产物"

declare -A JOBS=()
add_job() {
  local label="$1" file="$2" body="$3"
  if [[ -f "$file" ]]; then
    JOBS["$label"]="$body"
    ok "$label  $(basename "$file")  ($(wc -l < "$file") 行)"
  else
    warn "$label 缺失：$(basename "$file")"
  fi
}

add_job "match_predictions" \
  "$RUN_DIR/module2/rf4-predictions-test.tsv" \
  "{\"source\":\"random-forest\",\"location\":\"$RUN_DIR/module2/rf4-predictions-test.tsv\"}"

add_job "history_P6.1" "$RUN_DIR/module5/champions.tsv" \
  "{\"analysis\":\"P6.1\",\"location\":\"$RUN_DIR/module5/champions.tsv\"}"

add_job "history_P6.2" "$RUN_DIR/module5/home-advantage.tsv" \
  "{\"analysis\":\"P6.2\",\"location\":\"$RUN_DIR/module5/home-advantage.tsv\"}"

add_job "history_P6.4" "$RUN_DIR/module5/h2h.tsv" \
  "{\"analysis\":\"P6.4\",\"location\":\"$RUN_DIR/module5/h2h.tsv\"}"

if [[ ${#JOBS[@]} -eq 0 ]]; then
  err "没有任何可导入的文件"
  exit 3
fi

if [[ $DRY_RUN -eq 1 ]]; then
  step "演练结束"
  echo "  共 ${#JOBS[@]} 项待导入，去掉 --dry-run 执行实际写入"
  exit 0
fi

# ---------------------------------------------------------------------------
# 通过后端接口导入
# ---------------------------------------------------------------------------
step "调用导入接口"

if ! backend_health; then
  err "后端未就绪，先执行 scripts/10-start-backend.sh --daemon"
  exit 4
fi
ok "后端在线"

BASE="http://127.0.0.1:${BACKEND_PORT}"
FAILED=0

# 整体导入：一次调用覆盖全部产物
RESPONSE=$(curl -s -X POST "$BASE/api/admin/import/run/$RUN_ID_RESOLVED" \
  -H 'Content-Type: application/json' -d '{}' 2>/dev/null)
if echo "$RESPONSE" | grep -q '"status"'; then
  ok "整体导入完成"
  echo "$RESPONSE" | python -m json.tool 2>/dev/null | head -40 || echo "$RESPONSE"
else
  warn "整体导入未返回预期结果，改为逐项导入"
  for label in "${!JOBS[@]}"; do
    payload="${JOBS[$label]}"
    case "$label" in
      match_predictions)
        R=$(curl -s -X POST "$BASE/api/admin/import/matches/$RUN_ID_RESOLVED" \
            -H 'Content-Type: application/json' -d "$payload")
        ;;
      history_*)
        R=$(curl -s -X POST "$BASE/api/admin/import/history/$RUN_ID_RESOLVED" \
            -H 'Content-Type: application/json' -d "$payload")
        ;;
      *) continue ;;
    esac
    if echo "$R" | grep -q 'totalRows\|rowCounts'; then
      ok "$label"
    else
      err "$label 失败：$R"
      FAILED=$((FAILED+1))
    fi
  done
fi

step "核对写入结果"
if [[ -n "${MYSQL_PASSWORD:-}" ]] && command -v mysql >/dev/null 2>&1; then
  for t in match_prediction evaluation_metric player_profile \
           season_simulation history_point job_instance; do
    N=$(mysql -h "${MYSQL_HOST:-127.0.0.1}" -P "${MYSQL_PORT:-3306}" \
        -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" -N -B \
        -e "SELECT COUNT(*) FROM $t WHERE run_id='$RUN_ID_RESOLVED';" 2>/dev/null || echo "?")
    printf '  %-20s %s 行\n' "$t" "$N"
  done
fi

step "完成"
if [[ $FAILED -gt 0 ]]; then
  err "$FAILED 项失败"
  exit 5
fi
ok "全部导入成功"
echo
echo "  查看作业列表： curl http://127.0.0.1:${BACKEND_PORT}/api/jobs"
echo "  查看汇总：     curl http://127.0.0.1:${BACKEND_PORT}/api/jobs/summary"
