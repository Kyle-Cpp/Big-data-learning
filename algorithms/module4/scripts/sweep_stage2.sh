#!/bin/bash
# Improvement plan P1.3/P2.1 - stage 2 shrinkage sweep:
#   sigma2 (with stage-1 winning half-life/friendly-weight fixed) -> pick by
#   meanRPS on the valid split + check the thin-evidence top-table cleanup
# Usage: bash sweep_stage2.sh /FootPred/module1/work /path/to/jar 730 0.4

set -u
WORK="${1:-/FootPred/module1/work}"
JAR="${2:-footpred-algo-1.0.0.jar}"
HL="${3:-730}"
FW="${4:-0.4}"
SWEEP="$WORK/sweep_stage2"
MAXITER="${MAXITER:-60}"

mkdir -p "$SWEEP"
echo "stage2 sweep (half-life=$HL friendly=$FW fixed) -> $SWEEP"

for S2 in 0.5 1.0 2.0; do
  TAG="sigma2_${S2}"
  MODEL="$WORK/runs/$TAG/model.json"
  echo "=== sigma2=$S2 ==="
  hadoop jar "$JAR" train \
    --workdir "$WORK" \
    --run-id "$TAG" \
    --half-life-days "$HL" \
    --friendly-weight "$FW" \
    --sigma2 "$S2" \
    --max-iter "$MAXITER" > "$SWEEP/train_$TAG.log" 2>&1 || { echo "train FAILED, see log"; continue; }
  hadoop jar "$JAR" evaluate \
    --workdir "$WORK" --model "$MODEL" --run-id "eval_$TAG" \
    > "$SWEEP/eval_$TAG.txt" 2>&1 || { echo "evaluate FAILED"; continue; }
  grep "category=all" "$SWEEP/eval_$TAG.txt" | sed "s/^/[sigma2=$S2] /"
  # thin-evidence diagnostic travels with each training run (train log tail)
  grep -A 11 "thinnest-evidence" "$SWEEP/train_$TAG.log" | sed "s/^/[sigma2=$S2] /"
done

echo
echo "=== stage2 summary (sort by meanRPS of category=all) ==="
for f in "$SWEEP"/eval_*.txt; do
  TAG=$(basename "$f" .txt | sed 's/eval_//')
  RPS=$(grep "category=all" "$f" | grep -o 'meanRPS=[0-9.]*' | cut -d= -f2)
  ACC=$(grep "category=all" "$f" | grep -o 'accuracy=[0-9.]*' | cut -d= -f2)
  echo -e "$RPS\t$ACC\t$TAG"
done | sort -n
