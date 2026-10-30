#!/usr/bin/env bash
# =============================================================================
# 30-run-demo.sh —— 一键跑完整演示（不使用会重训的命令）
# =============================================================================
# 严格遵循 AI_HANDOFF.md 的约束：
#   - 不执行 club-all / international-all / all 等重训入口
#   - 每次用新 run-id，检测到已存在就拒绝
#   - 只用已验证的模型
#
# 用法：
#   bash scripts/30-run-demo.sh              # 完整流程
#   bash scripts/30-run-demo.sh --quick      # 只跑最快的两个
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

QUICK=0
[[ "${1:-}" == "--quick" ]] && QUICK=1

RUN_ID_RESOLVED="$(resolve_run_id)"
RUN_ROOT="$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED")"

step "运行计划"
echo "  RUN_ID   $RUN_ID_RESOLVED"
echo "  输出根   $RUN_ROOT"
echo "  模型     $MODEL_MODULE2_CLUB"
echo "           $MODEL_MODULE3"

# 关键安全约束：目标目录存在就停，绝不覆盖历史结果
if hdfs_exists "$RUN_ROOT"; then
  err "输出目录已存在：$RUN_ROOT"
  err "换一个 RUN_ID 后重试，例如："
  err "  RUN_ID=demo-$(date +%H%M%S) bash scripts/30-run-demo.sh"
  exit 3
fi

step "环境检查"
require_cmd hadoop || exit 2
require_file "$FOOTPRED_JAR_HANDOFF_MODULE2" || exit 2
require_file "$FOOTPRED_JAR_HANDOFF_MODULE3" || exit 2
require_file "$FOOTPRED_JAR_HANDOFF_MODULE5" || exit 2
ok "hadoop  $HADOOP_HOME"
ok "JAR 齐备"
require_dir_or_hdfs() {
  if hdfs_exists "$1"; then ok "HDFS $1"; else err "缺少 HDFS 路径：$1"; return 1; fi
}
require_dir_or_hdfs "$PREPARED_MODULE2/rf2-split/test" || exit 2
require_dir_or_hdfs "$MODEL_MODULE2_CLUB" || exit 2
require_dir_or_hdfs "$PREPARED_MODULE3/p4-0-shots" || exit 2
require_dir_or_hdfs "$MODEL_MODULE3" || exit 2

STARTED=$(date +%s)

# ---------------------------------------------------------------------------
# 模块二：RF4 预测 + RF5 评估（加载已有模型，不训练）
# ---------------------------------------------------------------------------
step "模块二 · RF4 预测"
run_hadoop "club-predict" hadoop jar "$FOOTPRED_JAR_HANDOFF_MODULE2" \
  -Dfootpred.rf.draw.margin="$FOOTPRED_DRAW_MARGIN_CLUB" \
  club-predict \
  "$PREPARED_MODULE2/rf2-split/test" \
  "$MODEL_MODULE2_CLUB" \
  "$RUN_ROOT/module2/rf4-predictions-test" || exit 10

step "模块二 · RF5 评估"
run_hadoop "club-evaluate" hadoop jar "$FOOTPRED_JAR_HANDOFF_MODULE2" \
  -Dfootpred.rf.draw.margin="$FOOTPRED_DRAW_MARGIN_CLUB" \
  club-evaluate \
  "$RUN_ROOT/module2/rf4-predictions-test" \
  "$RUN_ROOT/module2/rf5-evaluation-test" || exit 11

if [[ $QUICK -eq 1 ]]; then
  step "快速模式结束"
else
  # -------------------------------------------------------------------------
  # 模块三：xG 打分 + 球员聚合 + 评估
  # -------------------------------------------------------------------------
  step "模块三 · 球员 xG"
  run_hadoop "score" hadoop jar "$FOOTPRED_JAR_HANDOFF_MODULE3" \
    score \
    "$PREPARED_MODULE3/p4-0-shots" \
    "$PREPARED_MODULE3/p4-0-appearances" \
    "$MODEL_MODULE3" \
    "$RUN_ROOT/module3" || exit 20

  # -------------------------------------------------------------------------
  # 模块五：P6.1 / P6.2 / P6.4（P6.3 需要 StatsBomb 全量，默认跳过）
  # -------------------------------------------------------------------------
  for pair in "champions:p6-1-champions" "home-advantage:p6-2-home-advantage" \
              "h2h:p6-4-h2h"; do
    CMD="${pair%%:*}"; OUT="${pair##*:}"
    step "模块五 · $CMD"
    run_hadoop "$CMD" hadoop jar "$FOOTPRED_JAR_HANDOFF_MODULE5" \
      "$CMD" \
      "$PREPARED_MODULE2/rf0-clean" \
      "$RUN_ROOT/module5/$OUT" || exit 30
  done

  if [[ "${RUN_GOAL_MINUTES:-0}" == "1" ]]; then
    step "模块五 · P6.3 进球时间（较慢）"
    run_hadoop "goal-minutes" hadoop jar "$FOOTPRED_JAR_HANDOFF_MODULE5" \
      goal-minutes "$DATA_STATSBOMB_HDFS" "$RUN_ROOT/module5/p6-3" || exit 31
  fi
fi

ENDED=$(date +%s)
step "全部完成"
ok "耗时 $((ENDED-STARTED)) 秒"
ok "结果 $RUN_ROOT"
echo
echo "  下一步：导出结果给后端"
echo "    RUN_ID=$RUN_ID_RESOLVED bash scripts/20-export-results.sh"
echo
echo "  查看结果："
echo "    hdfs dfs -ls -R $RUN_ROOT"
