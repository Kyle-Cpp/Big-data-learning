#!/usr/bin/env bash
# =============================================================================
# 50-upload-to-vm.sh —— 把本机准备好的部署包上传到虚拟机
# =============================================================================
# 在 Windows 主机用 Git Bash 执行。
#
# 用法：
#   bash deploy/scripts/50-upload-to-vm.sh              # 上传后端与脚本
#   bash deploy/scripts/50-upload-to-vm.sh --with-data  # 同时上传数据与模型
#   bash deploy/scripts/50-upload-to-vm.sh --with-jars  # 同时上传算法 JAR
#
# 说明：
#   StatsBomb 完整数据约 12GB，不在交付包内，此脚本不会上传。
#   没有它时模块三（球员 xG）与 P6.3（进球时间）跑不了，其余模块正常。
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$ROOT_DIR/.." && pwd)"
ENV_FILE="$ROOT_DIR/config/footpred.env"

WITH_DATA=0
WITH_JARS=0
for arg in "$@"; do
  case "$arg" in
    --with-data) WITH_DATA=1 ;;
    --with-jars) WITH_JARS=1 ;;
  esac
done

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
err()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }

if [[ ! -f "$ENV_FILE" ]]; then
  err "缺少配置：$ENV_FILE"
  echo "  先执行： cp config/footpred.env.example config/footpred.env"
  exit 2
fi
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

REMOTE="${VM_USER}@${VM_HOST}"
SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=10 -p "${VM_SSH_PORT:-22}")
DEST="${VM_PROJECT_ROOT:-/job/footpred}"

step "目标"
ok "主机 ${VM_HOST}"
ok "用户 ${VM_USER}"
ok "目录 ${DEST}"

# ---------------------------------------------------------------------------
step "1. 连通性"
# ---------------------------------------------------------------------------
if ! ping -n 1 "$VM_HOST" >/dev/null 2>&1 && ! ping -c 1 "$VM_HOST" >/dev/null 2>&1; then
  err "ping 不通 ${VM_HOST}"
  exit 2
fi
ok "网络可达"

if ! ssh "${SSH_OPTS[@]}" "$REMOTE" "echo ok" >/dev/null 2>&1; then
  err "SSH 连不上。配置免密登录后重试："
  echo "    ssh-keygen -t rsa -N '' -f ~/.ssh/id_rsa    # 若还没有密钥"
  echo "    ssh-copy-id -p ${VM_SSH_PORT:-22} ${REMOTE}"
  exit 3
fi
ok "SSH 免密可用"

# ---------------------------------------------------------------------------
step "2. 创建远端目录"
# ---------------------------------------------------------------------------
ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p $DEST/{app,config,scripts,sql,logs,export,data}" || {
  err "创建目录失败"; exit 4;
}
ok "$DEST"

# ---------------------------------------------------------------------------
step "3. 上传后端 JAR"
# ---------------------------------------------------------------------------
BACKEND_JAR=$(find "$PROJECT_DIR/target" -maxdepth 1 -name 'footpred-backend-*.jar' \
  -not -name '*original*' 2>/dev/null | head -1)
if [[ -n "$BACKEND_JAR" ]]; then
  scp "${SSH_OPTS[@]}" "$BACKEND_JAR" "$REMOTE:$DEST/" >/dev/null 2>&1 \
    && ok "$(basename "$BACKEND_JAR")  ($(du -h "$BACKEND_JAR" | cut -f1))" \
    || { err "上传后端 JAR 失败"; exit 5; }
else
  err "找不到后端 JAR，先在主机执行 mvn package"
  exit 5
fi

# ---------------------------------------------------------------------------
step "4. 上传脚本与 SQL"
# ---------------------------------------------------------------------------
scp "${SSH_OPTS[@]}" "$ROOT_DIR"/scripts/*.sh "$REMOTE:$DEST/scripts/" >/dev/null 2>&1 \
  && ok "scripts/*.sh" || warn "脚本上传有问题"

scp "${SSH_OPTS[@]}" "$ROOT_DIR"/sql/*.sql "$REMOTE:$DEST/sql/" >/dev/null 2>&1 \
  && ok "sql/schema.sql" || warn "SQL 上传有问题"

scp "${SSH_OPTS[@]}" "$ROOT_DIR"/RUNBOOK.md "$ROOT_DIR"/VERIFICATION.md \
  "$REMOTE:$DEST/" >/dev/null 2>&1 && ok "文档"

ssh "${SSH_OPTS[@]}" "$REMOTE" "chmod +x $DEST/scripts/*.sh" && ok "已加可执行权限"

# ---------------------------------------------------------------------------
if [[ $WITH_JARS -eq 1 ]]; then
step "5. 上传算法 JAR"
# ---------------------------------------------------------------------------
  # 交接包：三个模块 JAR
  HANDOFF_ROOT=""
  for candidate in \
      "$PROJECT_DIR/../footpred-handoff-fixed" \
      "$PROJECT_DIR/../footpred-handoff" \
      "/d/VSCodePro/footpred-handoff-fixed"; do
    if [[ -d "$candidate/app" ]]; then HANDOFF_ROOT="$candidate"; break; fi
  done

  if [[ -n "$HANDOFF_ROOT" ]]; then
    for jar in footpred-module2.jar footpred-module3.jar footpred-module5.jar; do
      if [[ -f "$HANDOFF_ROOT/app/$jar" ]]; then
        scp "${SSH_OPTS[@]}" "$HANDOFF_ROOT/app/$jar" "$REMOTE:$DEST/app/" >/dev/null 2>&1 \
          && ok "$jar" || warn "$jar 失败"
      else
        warn "$jar 不存在"
      fi
    done
  else
    warn "找不到交接包 app/ 目录"
  fi

  # 搭档包：单一 uber-jar
  for candidate in \
      "$PROJECT_DIR/../FootPred_hadoop_mapreduce-main/release/footpred-algo-1.0.0.jar" \
      "/d/VSCodePro/FootPred_hadoop_mapreduce-main/release/footpred-algo-1.0.0.jar"; do
    if [[ -f "$candidate" ]]; then
      scp "${SSH_OPTS[@]}" "$candidate" "$REMOTE:$DEST/app/" >/dev/null 2>&1 \
        && ok "footpred-algo-1.0.0.jar" || warn "algo jar 失败"
      break
    fi
  done
fi

# ---------------------------------------------------------------------------
if [[ $WITH_DATA -eq 1 ]]; then
step "6. 上传数据与模型"
# ---------------------------------------------------------------------------
  for candidate in \
      "$PROJECT_DIR/../footpred-handoff-fixed" \
      "/d/VSCodePro/footpred-handoff-fixed"; do
    if [[ -d "$candidate/data" ]]; then DATA_ROOT="$candidate"; break; fi
  done

  if [[ -n "${DATA_ROOT:-}" ]]; then
    # 55 个联赛 CSV
    if [[ -d "$DATA_ROOT/data/raw-five-leagues" ]]; then
      ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p $DEST/data/raw-five-leagues"
      scp -r "${SSH_OPTS[@]}" "$DATA_ROOT/data/raw-five-leagues/"* \
        "$REMOTE:$DEST/data/raw-five-leagues/" >/dev/null 2>&1 && ok "55 个联赛 CSV"
    fi
    # 国家队
    if [[ -f "$DATA_ROOT/data/international/results.csv" ]]; then
      ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p $DEST/data/international"
      scp "${SSH_OPTS[@]}" "$DATA_ROOT/data/international/results.csv" \
        "$REMOTE:$DEST/data/international/" >/dev/null 2>&1 && ok "results.csv"
    fi
    # 模型
    if [[ -d "$DATA_ROOT/models" ]]; then
      ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p $DEST/data/models"
      scp -r "${SSH_OPTS[@]}" "$DATA_ROOT/models/"* "$REMOTE:$DEST/data/models/" \
        >/dev/null 2>&1 && ok "模型目录"
    fi
  else
    warn "找不到交接包 data/ 目录"
  fi
fi

# ---------------------------------------------------------------------------
step "7. 远端自检"
# ---------------------------------------------------------------------------
ssh "${SSH_OPTS[@]}" "$REMOTE" bash -s <<EOF
cd $DEST
echo "  后端 JAR:  \$(ls footpred-backend-*.jar 2>/dev/null | head -1)"
echo "  算法 JAR:  \$(ls app/*.jar 2>/dev/null | wc -l) 个"
echo "  脚本:      \$(ls scripts/*.sh 2>/dev/null | wc -l) 个"
echo "  数据:      \$(ls data/raw-five-leagues/*/*.csv 2>/dev/null | wc -l) 个 CSV"
echo "  hadoop:    \$(command -v hadoop || echo '未在 PATH')"
echo "  java:      \$(java -version 2>&1 | head -1)"
echo "  mysql:     \$(command -v mysql || echo '未在 PATH')"
EOF

step "完成"
echo "  下一步（在虚拟机里）："
echo "    cd $DEST"
echo "    bash scripts/00-detect-env.sh --write"
echo "    vi config/footpred.env"
echo "    bash scripts/12-status.sh"
