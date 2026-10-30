#!/usr/bin/env bash
set -euo pipefail
HADOOP=/training/hadoop-3.3.6/bin/hadoop
HDFS=/training/hadoop-3.3.6/bin/hdfs
JAR=${1:?Pass the new JAR path}
RUN=${2:-fivejobs-$(date +%Y%m%d-%H%M%S)}
WORK=/FootPred/module4
MODEL=/FootPred/module1/clubs/runs/leagues_v1/model.json
INPUT=/FootPred/data/leagues/results_leagues.csv
"$HDFS" dfs -test -e "$MODEL"
"$HDFS" dfs -test -e "$INPUT"
"$HADOOP" jar "$JAR" simulate --input "$INPUT" --model "$MODEL" \
  --workdir "$WORK" --run-id "$RUN" --total-sims 10000 --maps 2 --seed 42
"$HDFS" dfs -cat "$WORK/runs/$RUN/job_manifest.tsv"
"$HDFS" dfs -cat "$WORK/runs/$RUN/p5-4-evaluation/part-r-*"
echo "VALIDATION_RUN=$WORK/runs/$RUN"
