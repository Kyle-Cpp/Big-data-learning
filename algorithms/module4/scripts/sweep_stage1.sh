#!/bin/bash
# Improvement plan P1.3 - stage 1 hyper-parameter sweep:
#   half-life-days x friendly-weight -> pick by meanRPS on the valid split
# Run on the cluster after `prepare` (and with the improved jar deployed).
# Usage: bash sweep_stage1.sh /FootPred/module1/work /path/to/footpred-algo-1.0.0.jar

set -u
WORK="${1:-/FootPred/module1/work}"
JAR="${2:-footpred-algo-1.0.0.jar}"
SWEEP="$WORK/sweep_stage1"
MAXITER="${MAXITER:-60}"

mkdir -p "$SWEEP"
echo "stage1 sweep -> $SWEEP (maxIter=$MAXITER)"

for HL in 540 730 1095; do
  for FW in 0.3 0.4 0.5; do
    TAG="hl${HL}_fw${FW}"
    MODEL="$WORK/runs/$TAG/model.json"
    echo "=== $TAG ==="
    hadoop jar "$JAR" train \
      --workdir "$WORK" \
      --run-id "$TAG" \
      --half-life-days "$HL" \
      --friendly-weight "$FW" \
      --max-iter "$MAXITER" > "$SWEEP/train_$TAG.log" 2>&1 || { echo "train FAILED, see log"; continue; }
    hadoop jar "$JAR" evaluate \
      --workdir "$WORK" --model "$MODEL" --run-id "eval_$TAG" \
      > "$SWEEP/eval_$TAG.txt" 2>&1 || { echo "evaluate FAILED"; continue; }
    grep "category=all" "$SWEEP/eval_$TAG.txt" | sed "s/^/[$TAG] /"
  done
done

echo
echo "=== stage1 summary (sort by meanRPS of category=all) ==="
for f in "$SWEEP"/eval_*.txt; do
  TAG=$(basename "$f" .txt | sed 's/eval_//')
  RPS=$(grep "category=all" "$f" | grep -o 'meanRPS=[0-9.]*' | cut -d= -f2)
  ACC=$(grep "category=all" "$f" | grep -o 'accuracy=[0-9.]*' | cut -d= -f2)
  echo -e "$RPS\t$ACC\t$TAG"
done | sort -n
