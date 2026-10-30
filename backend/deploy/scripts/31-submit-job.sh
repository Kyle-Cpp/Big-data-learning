#!/usr/bin/env bash
# =============================================================================
# 31-submit-job.sh —— 通过后端 API 提交一个 MapReduce 作业
# =============================================================================
# 与 30-run-demo.sh 的区别：
#   30 直接调 hadoop jar（不经过后端）
#   31 走后端 API（会记录到 MySQL，可在前端看到进度）
#
# 前提：FOOTPRED_MYSQL_ENABLED=1 且 FOOTPRED_JOB_ENABLED=1
#
# 用法：
#   bash scripts/31-submit-job.sh club-predict
#   bash scripts/31-submit-job.sh score
#   bash scripts/31-submit-job.sh champions
#   bash scripts/31-submit-job.sh --list          # 列出可提交的命令
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

BASE="http://127.0.0.1:${BACKEND_PORT}"

if [[ "${1:-}" == "--list" ]] || [[ $# -eq 0 ]]; then
  step "可提交的命令（不含重训）"
  if backend_health; then
    curl -s "$BASE/api/catalog/jobs" \
      | python -c "
import json,sys
for j in json.load(sys.stdin):
    if not j['retrains']:
        req = ','.join(a['name'] for a in j['arguments'] if a['required'])
        print('  %-18s %-18s 必填: %s' % (j['id'], j['module'], req))
" 2>/dev/null
  else
    err "后端未就绪，先启动：bash scripts/10-start-backend.sh --daemon"
    exit 2
  fi
  exit 0
fi

COMMAND="$1"
RUN_ID_RESOLVED="$(resolve_run_id)"

step "准备提交"
ok "命令   $COMMAND"
ok "RUN_ID $RUN_ID_RESOLVED"

if ! backend_health; then
  err "后端未就绪"
  exit 2
fi

# 检查两个开关
HEALTH=$(curl -s "$BASE/api/jobs/summary" 2>/dev/null)
if ! echo "$HEALTH" | grep -q '{'; then
  err "作业元数据接口不可用，检查 FOOTPRED_MYSQL_ENABLED=1"
  exit 2
fi

# ---------------------------------------------------------------------------
# 按命令组装参数
# ---------------------------------------------------------------------------
# 参数值来自 footpred.env，集中在这里便于换环境时只改配置
case "$COMMAND" in
  club-predict)
    BODY=$(cat <<JSON
{"command":"club-predict","runId":"$RUN_ID_RESOLVED","arguments":{
  "feature-input":"$PREPARED_MODULE2/rf2-split/test",
  "model-path":"$MODEL_MODULE2_CLUB",
  "prediction-output":"$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module2/rf4-predictions-test")"
}}
JSON
)
    ;;
  club-evaluate)
    BODY=$(cat <<JSON
{"command":"club-evaluate","runId":"$RUN_ID_RESOLVED","arguments":{
  "prediction-input":"$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module2/rf4-predictions-test")",
  "evaluation-output":"$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module2/rf5-evaluation-test")"
}}
JSON
)
    ;;
  score)
    BODY=$(cat <<JSON
{"command":"score","runId":"$RUN_ID_RESOLVED","arguments":{
  "shots-input":"$PREPARED_MODULE3/p4-0-shots",
  "appearances-input":"$PREPARED_MODULE3/p4-0-appearances",
  "existing-model-dir":"$MODEL_MODULE3",
  "work-root":"$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module3")"
}}
JSON
)
    ;;
  champions|home-advantage|h2h)
    OUT="p6-1-champions"
    [[ "$COMMAND" == "home-advantage" ]] && OUT="p6-2-home-advantage"
    [[ "$COMMAND" == "h2h" ]] && OUT="p6-4-h2h"
    BODY=$(cat <<JSON
{"command":"$COMMAND","runId":"$RUN_ID_RESOLVED","arguments":{
  "clean-five-league-matches":"$PREPARED_MODULE2/rf0-clean",
  "output":"$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module5/$OUT")"
}}
JSON
)
    ;;
  predict)
    BODY=$(cat <<JSON
{"command":"predict","runId":"$RUN_ID_RESOLVED","arguments":{
  "model":"$(partner_path "/module1/work/model.json")",
  "fixtures":"$(partner_path "/module1/work/fixtures.tsv")",
  "out":"$(partner_path "/runs/$RUN_ID_RESOLVED/predictions")"
}}
JSON
)
    ;;
  simulate)
    BODY=$(cat <<JSON
{"command":"simulate","runId":"$RUN_ID_RESOLVED","arguments":{
  "input":"$(partner_path "/data/leagues/results_leagues.csv")",
  "workdir":"$(partner_path "/module4")",
  "model":"$(partner_path "/module4/runs/leagues_v1/model.json")",
  "out":"$(partner_path "/runs/$RUN_ID_RESOLVED/simulation")"
}}
JSON
)
    ;;
  *)
    err "未预置该命令的参数模板：$COMMAND"
    err "用 --list 查看，或用 curl 直接调 POST /api/jobs"
    exit 2
    ;;
esac

# ---------------------------------------------------------------------------
# 提交
# ---------------------------------------------------------------------------
step "提交作业"
RESPONSE=$(curl -s -X POST "$BASE/api/jobs" \
  -H 'Content-Type: application/json' -d "$BODY" 2>/dev/null)

echo "$RESPONSE" | python -m json.tool 2>/dev/null | head -20 || echo "$RESPONSE"

JOB_ID=$(echo "$RESPONSE" | python -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
if [[ -z "$JOB_ID" ]]; then
  err "提交失败，响应里没有 id"
  exit 3
fi

ok "作业 ID $JOB_ID"

# ---------------------------------------------------------------------------
# 轮询
# ---------------------------------------------------------------------------
step "跟踪进度（Ctrl-C 退出不影响作业）"
echo "  查看详情： curl $BASE/api/jobs/$JOB_ID"
echo "  查看日志： curl $BASE/api/jobs/$JOB_ID/log"
echo

for i in $(seq 1 300); do
  STATUS=$(curl -s "$BASE/api/jobs/$JOB_ID" \
    | python -c "
import json,sys
d=json.load(sys.stdin)
print('%s|%s|%s|%s' % (d.get('status','?'), d.get('progress','-'),
                      d.get('attemptInFlight','-'), d.get('yarnApplicationId','-')))
" 2>/dev/null)

  S=$(echo "$STATUS" | cut -d'|' -f1)
  P=$(echo "$STATUS" | cut -d'|' -f2)
  A=$(echo "$STATUS" | cut -d'|' -f4)
  printf '\r  [%3ds] %-12s progress=%-4s %s   ' "$((i*3))" "$S" "$P" "${A:--}"
  case "$S" in
    SUCCEEDED) echo; ok "作业成功"; break ;;
    FAILED|KILLED) echo; err "作业 $S"; break ;;
  esac
  sleep 3
done

echo
step "结果"
curl -s "$BASE/api/jobs/$JOB_ID" | python -m json.tool 2>/dev/null | head -30
echo
echo "  下一步导出结果： RUN_ID=$RUN_ID_RESOLVED bash scripts/20-export-results.sh"
