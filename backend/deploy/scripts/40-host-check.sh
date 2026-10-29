#!/usr/bin/env bash
# =============================================================================
# 40-host-check.sh —— 在 Windows 主机上检查能否连上虚拟机
# =============================================================================
# 用 Git Bash 执行（你已有 Git Bash）。
# 它回答一个问题：我这台机器现在能不能连到虚拟机的后端？
#
# 用法：
#   bash scripts/40-host-check.sh
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$ROOT_DIR/config/footpred.env"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
err()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }

if [[ ! -f "$ENV_FILE" ]]; then
  err "缺少配置：$ENV_FILE"
  echo "  复制模板后填入虚拟机 IP："
  echo "    cp config/footpred.env.example config/footpred.env"
  exit 2
fi
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

step "读取配置"
ok "VM_HOST      ${VM_HOST:-<未设置>}"
ok "VM_USER      ${VM_USER:-<未设置>}"
ok "BACKEND_PORT ${BACKEND_PORT:-<未设置>}"

# ---------------------------------------------------------------------------
step "1. 主机虚拟网卡"
# ---------------------------------------------------------------------------
if ipconfig 2>/dev/null | grep -q "VMnet8"; then
  VMNET8=$(ipconfig 2>/dev/null | grep -A5 "VMnet8" | grep -oE '192\.168\.[0-9]+\.[0-9]+' | head -1)
  ok "VMnet8 存在  主机侧 $VMNET8"
  SUBNET=$(printf '%s' "$VMNET8" | cut -d. -f1-3)
  VM_SUBNET=$(printf '%s' "${VM_HOST:-}" | cut -d. -f1-3)
  if [[ "$SUBNET" == "$VM_SUBNET" ]]; then
    ok "网段一致    $SUBNET.x"
  else
    err "网段不一致  主机 $SUBNET.x / 虚拟机 $VM_SUBNET.x"
    err "VM_HOST 填错了，或虚拟机的网卡模式不是 NAT"
  fi
else
  warn "未找到 VMnet8（虚拟机可能是桥接或仅主机模式）"
fi

# ---------------------------------------------------------------------------
step "2. ping 虚拟机"
# ---------------------------------------------------------------------------
if ping -n 2 "${VM_HOST}" >/dev/null 2>&1; then
  ok "ping 通"
else
  err "ping 不通：${VM_HOST}"
  echo "     排查顺序："
  echo "       1) 虚拟机是否已启动？"
  echo "       2) 虚拟机内 firewall-cmd --state 是否 running？"
  echo "       3) 虚拟机 IP 是否变了？（NAT 下 DHCP 可能换地址）"
  echo "       4) 在虚拟机里跑 ip addr show 确认实际 IP"
  exit 1
fi

# ---------------------------------------------------------------------------
step "3. SSH"
# ---------------------------------------------------------------------------
if command -v ssh >/dev/null 2>&1; then
  if ssh -o ConnectTimeout=5 -o BatchMode=yes -o StrictHostKeyChecking=no \
        -p "${VM_SSH_PORT:-22}" "${VM_USER}@${VM_HOST}" "echo ok" >/dev/null 2>&1; then
    ok "免密 SSH 可用"
  else
    warn "SSH 需要密码或未配置密钥（不影响 HTTP 访问）"
    echo "     可选：配置免密登录"
    echo "       ssh-keygen -t rsa          # 若还没有密钥"
    echo "       ssh-copy-id -p ${VM_SSH_PORT:-22} ${VM_USER}@${VM_HOST}"
  fi
else
  warn "主机无 ssh 命令"
fi

# ---------------------------------------------------------------------------
step "4. 后端 HTTP"
# ---------------------------------------------------------------------------
URL="http://${VM_HOST}:${BACKEND_PORT}/api/catalog/jobs"
if curl -sf -m 5 "$URL" >/dev/null 2>&1; then
  ok "后端可访问"
  COUNT=$(curl -s -m 5 "$URL" | grep -o '"id"' | wc -l)
  ok "作业命令 $COUNT 个"
  echo
  echo "     浏览器打开： http://${VM_HOST}:${BACKEND_PORT}/api/catalog/jobs"
else
  err "后端不可达：$URL"
  echo "     在虚拟机里执行： bash scripts/12-status.sh"
  echo "     常见原因："
  echo "       - 后端没启动"
  echo "       - BACKEND_HOST 是 127.0.0.1（改成 0.0.0.0）"
  echo "       - firewalld 挡住了端口，执行："
  echo "           firewall-cmd --permanent --add-port=${BACKEND_PORT}/tcp && firewall-cmd --reload"
fi

# ---------------------------------------------------------------------------
step "5. Hadoop Web UI"
# ---------------------------------------------------------------------------
for pair in "9870:NameNode" "8088:YARN" "16010:HBase"; do
  PORT="${pair%%:*}"; NAME="${pair##*:}"
  if curl -sf -m 3 "http://${VM_HOST}:${PORT}" >/dev/null 2>&1; then
    ok "$NAME  http://${VM_HOST}:${PORT}"
  else
    warn "$NAME  (:$PORT) 不可达"
  fi
done

printf '\n'
