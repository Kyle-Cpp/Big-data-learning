#!/usr/bin/env bash
# =============================================================================
# 21-init-mysql.sh —— 初始化 MySQL 元数据库
# =============================================================================
# 在虚拟机里执行。创建库、表，以及后端专用账号。
#
# 用法：
#   bash scripts/21-init-mysql.sh                 # 用配置里的 root 免密或交互输入
#   MYSQL_ROOT_PASSWORD=xxx bash scripts/21-init-mysql.sh
# =============================================================================
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

require_var MYSQL_DATABASE || exit 2
require_var MYSQL_USER     || exit 2
require_var MYSQL_PASSWORD || exit 2
require_cmd mysql          || exit 2

step "配置"
ok "数据库   $MYSQL_DATABASE"
ok "账号     $MYSQL_USER"
ok "主机     $MYSQL_HOST:$MYSQL_PORT"

# ---------------------------------------------------------------------------
# 连接参数
# ---------------------------------------------------------------------------
CONN=(-h "$MYSQL_HOST" -P "$MYSQL_PORT")
if [[ -n "${MYSQL_ROOT_PASSWORD:-}" ]]; then
  CONN+=("-p${MYSQL_ROOT_PASSWORD}")
elif [[ -n "${MYSQL_ROOT_USER:-}" ]]; then
  # 用户名单独给出时用 -u，密码走交互
  :
fi
# root 用户名默认 root
ROOT_USER="${MYSQL_ROOT_USER:-root}"
CONN+=("-u" "$ROOT_USER")

step "测试连接"
if ! mysql "${CONN[@]}" -e "SELECT 1" >/dev/null 2>&1; then
  err "无法连接 MySQL。请设置 root 密码："
  err "  MYSQL_ROOT_PASSWORD=你的密码 bash scripts/21-init-mysql.sh"
  echo
  echo "  如果 root 用 socket 认证（CentOS 默认），改用："
  echo "    sudo mysql -e \"SELECT 1\"    # 确认能进"
  echo "    然后手动执行 deploy/sql/schema.sql"
  exit 2
fi
ok "连接成功"

# ---------------------------------------------------------------------------
# 建库建表
# ---------------------------------------------------------------------------
step "创建库与表"

SCHEMA_FILE="$ROOT_DIR/sql/schema.sql"
require_file "$SCHEMA_FILE" || exit 2

# 只在库不存在时创建，避免误删已有数据
mysql "${CONN[@]}" -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\`
  DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;" || {
  err "创建数据库失败"; exit 3;
}
ok "数据库 $MYSQL_DATABASE"

# 逐表执行 DDL（IF NOT EXISTS，可重复运行）
mysql "${CONN[@]}" "$MYSQL_DATABASE" < "$SCHEMA_FILE" || {
  err "执行 schema.sql 失败"; exit 4;
}
ok "表结构已就绪"

TABLES=$(mysql "${CONN[@]}" -N -B -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}';")
ok "表数量 ${TABLES}"

for t in job_instance job_attempt match_prediction evaluation_metric \
         player_profile season_simulation history_point; do
  EXISTS=$(mysql "${CONN[@]}" -N -B -e \
    "SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='${MYSQL_DATABASE}' AND table_name='${t}';")
  if [[ "$EXISTS" == "1" ]]; then ok "$t"; else err "$t 缺失"; fi
done

# ---------------------------------------------------------------------------
# 建账号
# ---------------------------------------------------------------------------
step "创建应用账号"

# 允许从本机与局域网连接：虚拟机内后端用 localhost，
# 若后端跑在主机上则需要 %。这里两个都建，便于排查。
mysql "${CONN[@]}" <<SQL || warn "账号创建有告警，可能已存在"
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'localhost' IDENTIFIED BY '${MYSQL_PASSWORD}';
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
SQL
ok "账号 ${MYSQL_USER}@localhost 与 @%"

step "验证应用账号"
if mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" \
     "$MYSQL_DATABASE" -e "SELECT 1" >/dev/null 2>&1; then
  ok "应用账号可登录"
  ROWS=$(mysql -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASSWORD" \
     "$MYSQL_DATABASE" -N -B -e "SELECT COUNT(*) FROM job_instance;" 2>/dev/null)
  ok "job_instance 可读，当前 ${ROWS} 行"
else
  err "应用账号登录失败，检查 MYSQL_PASSWORD 是否有特殊字符"
  err "密码含 ! @ # 等字符时，在 footpred.env 里用单引号包住"
  exit 5
fi

step "完成"
echo
echo "  后端需要这几项（在 footpred.env 中）："
echo "    FOOTPRED_MYSQL_ENABLED=1"
echo "    SPRING_DATASOURCE_URL=jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
echo "    SPRING_DATASOURCE_USERNAME=${MYSQL_USER}"
echo "    SPRING_DATASOURCE_PASSWORD=******"
echo
echo "  然后重启后端：bash scripts/11-stop-backend.sh && bash scripts/10-start-backend.sh --daemon"
