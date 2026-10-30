#!/usr/bin/env bash
# =============================================================================
# 20-export-results.sh —— 把 HDFS 结果导出到本地，供后端读取
# =============================================================================
# 后端跑在虚拟机上，读 HDFS 需要额外的 Hadoop 客户端类路径。
# 更简单可靠的做法是：把 part-* 合并成 tsv 落到本地，后端读普通文件。
# 这个脚本就是那道桥。
#
# 用法：
#   bash scripts/20-export-results.sh              # 导出当前 RUN_ID 的结果
#   RUN_ID=20261025-120000 bash scripts/20-export-results.sh
#   bash scripts/20-export-results.sh --models     # 只导出模型（首次部署用）
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

MODELS_ONLY=0
[[ "${1:-}" == "--models" ]] && MODELS_ONLY=1

RUN_ID_RESOLVED="$(resolve_run_id)"
EXPORT_ROOT="${FOOTPRED_EXPORT_ROOT:-$ROOT_DIR/export}"
mkdir -p "$EXPORT_ROOT"

step "导出目录"
ok "$EXPORT_ROOT"
ok "RUN_ID $RUN_ID_RESOLVED"

# ---------------------------------------------------------------------------
# 1. 模型（后端 /api/models 依赖）
# ---------------------------------------------------------------------------
step "模型"

MODEL_DST="$EXPORT_ROOT/handoff/models"
mkdir -p "$MODEL_DST/module2/club-rf-v4" "$MODEL_DST/module2/international-rf-v1" "$MODEL_DST/module3/player-xg-v1"

export_model_dir() {
  local src="$1" dst="$2"
  if ! hdfs_exists "$src"; then
    warn "源不存在，跳过：$src"
    return 1
  fi
  mkdir -p "$dst"
  # 模型是单个 JSON 行文件，直接取 part-r-00000
  if hdfs dfs -test -e "$src/part-r-00000" 2>/dev/null; then
    hdfs dfs -get -f "$src/part-r-00000" "$dst/part-r-00000" 2>/dev/null
    ok "$(basename "$dst")/part-r-00000  ($(wc -c < "$dst/part-r-00000") 字节)"
  else
    # 模型目录可能是 model.json
    if hdfs dfs -test -e "$src/model.json" 2>/dev/null; then
      hdfs dfs -get -f "$src/model.json" "$dst/model.json" 2>/dev/null
      ok "$(basename "$dst")/model.json"
    else
      warn "未找到 part-r-00000 或 model.json：$src"
      return 1
    fi
  fi
}

export_model_dir "$MODEL_MODULE2_CLUB" "$MODEL_DST/module2/club-rf-v4"
export_model_dir "$MODEL_MODULE2_INTL" "$MODEL_DST/module2/international-rf-v1"
export_model_dir "$MODEL_MODULE3"      "$MODEL_DST/module3/player-xg-v1"

# 搭档包的 DC 模型与模拟结果
PARTNER_MODELS="$EXPORT_ROOT/handoff/models"
for f in model_national.json model_clubs_v1.json real_final_table.tsv \
         simulation_leagues_v1_10k.tsv teams_by_league.tsv; do
  SRC="$(partner_path "/models/$f")"
  if hdfs_exists "$SRC"; then
    hdfs dfs -get -f "$SRC" "$PARTNER_MODELS/$f" 2>/dev/null && ok "$f"
  else
    # 也尝试交接包本地副本
    LOCAL_GUESS="$ROOT_DIR/handoff/models/$f"
    if [[ -f "$LOCAL_GUESS" ]]; then
      cp "$LOCAL_GUESS" "$PARTNER_MODELS/$f" && ok "$f（来自本地副本）"
    else
      warn "$f 未找到（HDFS 与本地均无）"
    fi
  fi
done

if [[ $MODELS_ONLY -eq 1 ]]; then
  step "完成（仅模型）"
  echo "模型已导出到：$MODEL_DST"
  exit 0
fi

# ---------------------------------------------------------------------------
# 2. 模块二：预测与评估
# ---------------------------------------------------------------------------
step "模块二结果"

RUN_ROOT="$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module2")"
M2_DST="$EXPORT_ROOT/handoff/models/module2/club-rf-v4"

for split in validation test backtest; do
  # 预测：11 列，带表头
  export_result_dir \
    "$RUN_ROOT/rf4-predictions-$split" \
    "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/module2/rf4-predictions-$split.tsv" \
    "match_id	epoch_day	season	league	home_team	away_team	actual	predicted	p_home	p_draw	p_away"

  # 评估：key-value，无表头
  export_result_dir \
    "$RUN_ROOT/rf5-evaluation-$split" \
    "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/module2/evaluation-$split.tsv"
done

# 后端默认从 club-rf-v4 目录读评估；把最新一次覆盖过去
for split in validation test backtest; do
  SRC="$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/module2/evaluation-$split.tsv"
  if [[ -s "$SRC" ]]; then
    mkdir -p "$M2_DST"
    cp "$SRC" "$M2_DST/evaluation-$split.tsv"
  fi
done

# ---------------------------------------------------------------------------
# 3. 模块三：球员
# ---------------------------------------------------------------------------
step "模块三结果"

M3_RUN="$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module3")"
export_result_dir "$M3_RUN/p4-4-player-predictions" \
  "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/module3/players.tsv"
export_result_dir "$M3_RUN/p4-6-evaluation" \
  "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/module3/evaluation.tsv"

# ---------------------------------------------------------------------------
# 4. 模块五：P6.1-P6.4
# ---------------------------------------------------------------------------
step "模块五结果"

for item in "p6-1-champions:champions" "p6-2-home-advantage:home-advantage" \
            "p6-3-goal-minutes:goal-minutes" "p6-4-h2h:h2h"; do
  DIR="${item%%:*}"; NAME="${item##*:}"
  export_result_dir \
    "$(handoff_path "/handoff/runs/$RUN_ID_RESOLVED/module5/$DIR")" \
    "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/module5/$NAME.tsv"
done

# ---------------------------------------------------------------------------
# 5. 搭档包：DC 预测与 P6.5-P6.8
# ---------------------------------------------------------------------------
step "搭档包结果"

P_RUN="$(partner_path "/runs/$RUN_ID_RESOLVED")"
export_result_dir "$P_RUN/predictions" \
  "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/partner/predictions.tsv"
export_result_dir "$P_RUN/evaluation" \
  "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/partner/evaluation.tsv"

for a in p65 p66 p67 p68; do
  for sub in career messi_club nationality; do
    SRC="$P_RUN/$a/$sub"
    if hdfs_exists "$SRC"; then
      export_result_dir "$SRC" "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/partner/$a-$sub.tsv"
    fi
  done
  # p67/p66 直接是 part-r-*
  if hdfs_exists "$P_RUN/$a" && ! hdfs dfs -ls "$P_RUN/$a" 2>/dev/null | grep -q "/career\|/messi_club\|/nationality"; then
    export_result_dir "$P_RUN/$a" "$EXPORT_ROOT/runs/$RUN_ID_RESOLVED/partner/$a.tsv"
  fi
done

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
step "完成"
COUNT=$(find "$EXPORT_ROOT" -name '*.tsv' -o -name 'part-r-00000' -o -name 'model.json' 2>/dev/null | wc -l)
ok "共 $COUNT 个文件"
ok "位置 $EXPORT_ROOT"
echo
echo "  后端读取根目录需为：$EXPORT_ROOT"
echo "  检查配置：grep FOOTPRED_LOCAL_ROOT $ENV_FILE"
echo
echo "  目录预览："
find "$EXPORT_ROOT" -name '*.tsv' 2>/dev/null | head -20 | sed 's/^/    /'
