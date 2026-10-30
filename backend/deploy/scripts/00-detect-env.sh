#!/usr/bin/env bash
# =============================================================================
# 00-detect-env.sh —— 自动探测环境，生成 footpred.env 建议值
# =============================================================================
# 在虚拟机里执行。它不修改任何系统配置，只读探测并打印结果。
#
# 用法：
#   bash scripts/00-detect-env.sh              # 只打印
#   bash scripts/00-detect-env.sh --write      # 直接生成 config/footpred.env
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_FILE="$ROOT_DIR/config/footpred.env"
WRITE=0
[[ "${1:-}" == "--write" ]] && WRITE=1

ok()   { printf '  \033[32m✓\033[0m %-22s %s\n' "$1" "${2:-}"; }
bad()  { printf '  \033[31m✗\033[0m %-22s %s\n' "$1" "${2:-}"; }
warn() { printf '  \033[33m!\033[0m %-22s %s\n' "$1" "${2:-}"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---------------------------------------------------------------------------
# 1. 主机名与 IP
# ---------------------------------------------------------------------------
head_ "1. 网络"
HOSTNAME_FQDN="$(hostname -f 2>/dev/null || hostname)"
PRIMARY_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
ok "hostname" "$HOSTNAME_FQDN"
ok "IP" "$PRIMARY_IP"

# 判断是否 NAT 网段（VMware 默认 192.168.x.x）
case "$PRIMARY_IP" in
  192.168.*) warn "网络类型" "VMware NAT（IP 由 DHCP 分配，重启可能变化，建议静态化）" ;;
  10.*|172.*) warn "网络类型" "私有网段，请确认主机能否访问" ;;
  *)          ok "网络类型" "非典型私网地址" ;;
esac

# ---------------------------------------------------------------------------
# 2. Java
# ---------------------------------------------------------------------------
head_ "2. Java"
JAVA_BIN="$(command -v java 2>/dev/null || true)"
if [[ -n "$JAVA_BIN" ]]; then
  JV="$(java -version 2>&1 | head -1)"
  ok "java" "$JAVA_BIN"
  ok "版本" "$JV"
  # JDK 目录 = java 的上级的上级；软链接时用 readlink -f
  REAL_JAVA="$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")"
  DETECTED_JAVA_HOME="$(cd "$(dirname "$REAL_JAVA")/.." && pwd)"
  ok "建议 JAVA_HOME" "$DETECTED_JAVA_HOME"
  case "$JV" in
    *\"1.8.*) : ;;
    *) warn "版本提示" "三份交付均要求 Java 8 字节码，当前不是 1.8" ;;
  esac
else
  bad "java" "未找到，请检查 PATH"
  DETECTED_JAVA_HOME=""
fi

# ---------------------------------------------------------------------------
# 3. Hadoop
# ---------------------------------------------------------------------------
head_ "3. Hadoop"
HADOOP_BIN="$(command -v hadoop 2>/dev/null || true)"
if [[ -n "$HADOOP_BIN" ]]; then
  REAL_HADOOP="$(readlink -f "$HADOOP_BIN" 2>/dev/null || echo "$HADOOP_BIN")"
  # .../hadoop-3.3.6/bin/hadoop -> .../hadoop-3.3.6
  DETECTED_HADOOP_HOME="$(cd "$(dirname "$REAL_HADOOP")/.." && pwd)"
  ok "hadoop" "$REAL_HADOOP"
  ok "建议 HADOOP_HOME" "$DETECTED_HADOOP_HOME"
  if [[ -d "$DETECTED_HADOOP_HOME/etc/hadoop" ]]; then
    ok "HADOOP_CONF_DIR" "$DETECTED_HADOOP_HOME/etc/hadoop"
    DETECTED_HADOOP_CONF="$DETECTED_HADOOP_HOME/etc/hadoop"
  else
    bad "conf 目录" "未找到 etc/hadoop"
    DETECTED_HADOOP_CONF=""
  fi
  ok "版本" "$(hadoop version 2>/dev/null | head -1)"
else
  bad "hadoop" "未找到。若装在非标准位置，请手动填 HADOOP_HOME"
  DETECTED_HADOOP_HOME=""; DETECTED_HADOOP_CONF=""
fi

# ---------------------------------------------------------------------------
# 4. HDFS 状态
# ---------------------------------------------------------------------------
head_ "4. HDFS"
if command -v hdfs >/dev/null 2>&1; then
  if hdfs dfsadmin -report >/dev/null 2>&1; then
    ok "状态" "NameNode 可用"
    CAP="$(hdfs dfsadmin -report 2>/dev/null | grep -m1 'Configured Capacity' | awk -F': ' '{print $2}')"
    [[ -n "${CAP:-}" ]] && ok "总容量" "$CAP"
    for p in /footpred /FootPred; do
      if hdfs dfs -test -e "$p" 2>/dev/null; then
        ok "已存在" "$p"
      else
        warn "不存在" "$p（首次部署时由脚本创建）"
      fi
    done
  else
    bad "状态" "NameNode 不可用，先执行 start-dfs.sh"
  fi
else
  bad "hdfs" "命令未找到"
fi

# ---------------------------------------------------------------------------
# 5. YARN
# ---------------------------------------------------------------------------
head_ "5. YARN"
if command -v yarn >/dev/null 2>&1; then
  if yarn node -list 2>/dev/null | grep -q "Total Nodes"; then
    NODES="$(yarn node -list 2>/dev/null | grep -oP 'Total Nodes:\s*\K\d+' || echo '?')"
    ok "NodeManager" "共 $NODES 个节点"
  else
    warn "状态" "ResourceManager 未响应，可能未启动"
  fi
else
  bad "yarn" "命令未找到"
fi

# ---------------------------------------------------------------------------
# 6. HBase
# ---------------------------------------------------------------------------
head_ "6. HBase"
if command -v hbase >/dev/null 2>&1; then
  REAL_HBASE="$(readlink -f "$(command -v hbase)" 2>/dev/null || command -v hbase)"
  DETECTED_HBASE_HOME="$(cd "$(dirname "$REAL_HBASE")/.." && pwd)"
  ok "hbase" "$REAL_HBASE"
  ok "建议 HBASE_HOME" "$DETECTED_HBASE_HOME"
  ok "版本" "$(hbase version 2>/dev/null | head -1)"
  if echo 'list' | timeout 10 hbase shell >/dev/null 2>&1; then
    ok "状态" "HBase shell 可用"
  else
    warn "状态" "shell 无响应，检查 HMaster 是否启动"
  fi
else
  bad "hbase" "命令未找到（本项目实际未使用 HBase 表，仅确认环境）"
  DETECTED_HBASE_HOME=""
fi

# ---------------------------------------------------------------------------
# 7. MySQL
# ---------------------------------------------------------------------------
head_ "7. MySQL"
MYSQL_BIN="$(command -v mysql 2>/dev/null || true)"
if [[ -n "$MYSQL_BIN" ]]; then
  ok "mysql" "$MYSQL_BIN"
  ok "版本" "$(mysql --version 2>/dev/null)"
  if systemctl is-active --quiet mysqld 2>/dev/null || systemctl is-active --quiet mysql 2>/dev/null; then
    ok "服务" "运行中"
  else
    warn "服务" "未在运行，执行 systemctl start mysqld"
  fi
else
  bad "mysql" "命令未找到"
fi

# ---------------------------------------------------------------------------
# 8. 项目 JAR
# ---------------------------------------------------------------------------
head_ "8. 项目 JAR"
for jar in footpred-module2.jar footpred-module3.jar footpred-module5.jar footpred-algo-1.0.0.jar; do
  FOUND="$(find "$ROOT_DIR" -maxdepth 3 -name "$jar" 2>/dev/null | head -1)"
  if [[ -n "$FOUND" ]]; then ok "$jar" "$FOUND"; else warn "$jar" "未找到"; fi
done

# ---------------------------------------------------------------------------
# 9. 后端
# ---------------------------------------------------------------------------
head_ "9. 后端"
BACKEND_JAR="$(find "$ROOT_DIR" -maxdepth 3 -name 'footpred-backend-*.jar' 2>/dev/null | head -1)"
if [[ -n "$BACKEND_JAR" ]]; then
  ok "后端 JAR" "$BACKEND_JAR"
else
  warn "后端 JAR" "未找到，需在主机构建后拷入"
fi

# ---------------------------------------------------------------------------
# 10. 端口占用
# ---------------------------------------------------------------------------
head_ "10. 端口"
for port in 8088 9000 9870 8080 9090 16010 3306; do
  if command -v ss >/dev/null 2>&1; then
    if ss -tln 2>/dev/null | grep -q ":$port "; then
      ok "$port" "已监听"
    else
      warn "$port" "未监听"
    fi
  fi
done

# ---------------------------------------------------------------------------
# 生成建议配置
# ---------------------------------------------------------------------------
head_ "结论"
cat <<EOF

  建议写入 footpred.env 的值：

    VM_HOST=$PRIMARY_IP
    JAVA_HOME=${DETECTED_JAVA_HOME:-<未探测到>}
    HADOOP_HOME=${DETECTED_HADOOP_HOME:-<未探测到>}
    HADOOP_CONF_DIR=${DETECTED_HADOOP_CONF:-<未探测到>}
    HBASE_HOME=${DETECTED_HBASE_HOME:-<未探测到>}
    YARN_RM_HOST=$PRIMARY_IP
    FOOTPRED_LOCAL_ROOT=$ROOT_DIR/export

EOF

if [[ $WRITE -eq 1 ]]; then
  if [[ -f "$OUT_FILE" ]]; then
    cp "$OUT_FILE" "$OUT_FILE.bak.$(date +%Y%m%d-%H%M%S)"
    echo "  已备份原文件"
  fi
  cp "$ROOT_DIR/config/footpred.env.example" "$OUT_FILE"
  # 只替换确定探测到的值，探测不到的保留模板默认
  [[ -n "${DETECTED_JAVA_HOME:-}" ]]   && sed -i "s|^JAVA_HOME=.*|JAVA_HOME=$DETECTED_JAVA_HOME|" "$OUT_FILE"
  [[ -n "${DETECTED_HADOOP_HOME:-}" ]] && sed -i "s|^HADOOP_HOME=.*|HADOOP_HOME=$DETECTED_HADOOP_HOME|" "$OUT_FILE"
  [[ -n "${DETECTED_HADOOP_CONF:-}" ]] && sed -i "s|^HADOOP_CONF_DIR=.*|HADOOP_CONF_DIR=$DETECTED_HADOOP_CONF|" "$OUT_FILE"
  [[ -n "${DETECTED_HBASE_HOME:-}" ]]  && sed -i "s|^HBASE_HOME=.*|HBASE_HOME=$DETECTED_HBASE_HOME|" "$OUT_FILE"
  [[ -n "${PRIMARY_IP:-}" ]]           && sed -i "s|^VM_HOST=.*|VM_HOST=$PRIMARY_IP|; s|^YARN_RM_HOST=.*|YARN_RM_HOST=$PRIMARY_IP|" "$OUT_FILE"
  sed -i "s|^FOOTPRED_LOCAL_ROOT=.*|FOOTPRED_LOCAL_ROOT=$ROOT_DIR/export|" "$OUT_FILE"
  sed -i "s|^FOOTPRED_EXPORT_ROOT=.*|FOOTPRED_EXPORT_ROOT=$ROOT_DIR/export|" "$OUT_FILE"
  echo "  已生成：$OUT_FILE"
  echo "  请人工核对 VM_HOST 与 MySQL 密码后再启动。"
else
  echo "  加 --write 可直接生成 config/footpred.env"
fi
