# FootPred 部署运行手册

面向"换一台电脑、换一个 IP 也能跑通"的场景。核心原则：**只改一个文件**。

---

## 0. 先理解这套部署的结构

```
Windows 主机                          CentOS 7 虚拟机
┌──────────────────────┐              ┌─────────────────────────────┐
│ 浏览器                │              │ Hadoop 3.3.6 (HDFS+YARN)    │
│   ↓ http://VM_IP:8080│  VMware NAT  │ HBase 2.4.18                │
│ Git Bash（可选）      │ ──────────▶  │ MySQL 8.0.31                │
│   scripts/40-host-*  │              │ 后端 jar（Spring Boot）      │
└──────────────────────┘              │   ↓ 读本地 export 目录       │
                                      │ 三个算法 JAR                 │
                                      └─────────────────────────────┘
```

**为什么后端读本地文件而不是直接读 HDFS**：后端在虚拟机上虽然能 `hdfs dfs -cat`，但让 Spring Boot 加载整套 Hadoop 客户端类路径会引入版本冲突（`hadoop-client` 与 Spring Boot 的依赖树容易打架）。用"作业写完 → 导出成 tsv → 后端读普通文件"这条链路，后端零 Hadoop 依赖，稳定得多，也便于在没有 Hadoop 的机器上开发前端。

---

## 1. 第一次部署（每台新电脑做一次）

### 1.1 主机侧：构建后端

```bash
cd D:\VSCodePro\footpred-backend
mvn clean package -DskipTests
# 产物：target/footpred-backend-1.0.0.jar
```

> Maven 不在 PATH 时用完整路径：
> `C:\Users\<你>\.m2\wrapper\dists\apache-maven-3.8.4-bin\*\apache-maven-3.8.4\bin\mvn`
> 且需 `JAVA_HOME` 指向 JDK 8。

### 1.2 主机侧：启动虚拟机并查 IP

启动 CentOS 7 后，在**虚拟机终端**执行：

```bash
ip addr show | grep 'inet '
```

找到形如 `192.168.<网段>.x` 的地址（不是 `127.0.0.1`）。

### 1.3 把部署包传进虚拟机

三种方式，选一种：

**方式 A：VMware 共享文件夹**（推荐，一次配置长期有效）
VMware → 虚拟机设置 → 选项 → 共享文件夹 → 添加 `D:\VSCodePro` → 在虚拟机里访问 `/mnt/hgfs/VSCodePro`

**方式 B：scp**
```bash
scp -r D:\VSCodePro\footpred-backend root@<VM_IP>:/job/footpred
```

**方式 C：直接用本机文件（若你就在 VM 里操作）**

虚拟机内最终目录应为：

```
/job/footpred/
├── config/footpred.env
├── scripts/*.sh
├── footpred-backend-1.0.0.jar
├── app/footpred-module2.jar
├── app/footpred-module3.jar
├── app/footpred-module5.jar
└── export/                     # 后端读取根目录
```

### 1.4 虚拟机侧：自动探测环境

```bash
cd /job/footpred
chmod +x scripts/*.sh
bash scripts/00-detect-env.sh
```

它会打印出建议的 `JAVA_HOME`、`HADOOP_HOME`、`HADOOP_CONF_DIR`、`HBASE_HOME` 和本机 IP。**这就是你不用自己找路径的原因**——它从 `which hadoop` 反推安装目录。

确认无误后直接生成配置文件：

```bash
bash scripts/00-detect-env.sh --write
```

### 1.5 人工核对配置（唯一需要动手的地方）

```bash
vi config/footpred.env
```

**必须核对这 4 项**：

| 配置项 | 怎么填 |
|---|---|
| `VM_HOST` | 1.2 步查到的 IP（脚本已自动填） |
| `MYSQL_PASSWORD` | 你的 MySQL 密码 |
| `FOOTPRED_LOCAL_ROOT` | 保持脚本填的 `/job/footpred/export` |
| `HADOOP_HOME` | 脚本探测的路径；探测失败时手动填 |

其余项在标准环境下不用改。

### 1.6 启动基础服务

```bash
# Hadoop
start-dfs.sh
start-yarn.sh

# HBase（本项目实际未用 HBase 表，但确认环境可用）
start-hbase.sh

# MySQL
systemctl start mysqld
```

### 1.6.1 初始化 MySQL（仅首次）

```bash
# 建库、建表、建账号。root 密码按需传入
MYSQL_ROOT_PASSWORD=你的root密码 bash scripts/21-init-mysql.sh
```

若 root 用 socket 认证（CentOS 默认配置），改成：

```bash
sudo mysql < deploy/sql/schema.sql
# 再手动建账号
sudo mysql -e "CREATE USER IF NOT EXISTS 'footpred'@'localhost' IDENTIFIED BY '你的密码';"
sudo mysql -e "GRANT SELECT,INSERT,UPDATE,DELETE ON footpred.* TO 'footpred'@'localhost';"
```

然后启用开关：

```bash
sed -i 's/^FOOTPRED_MYSQL_ENABLED=.*/FOOTPRED_MYSQL_ENABLED=1/' config/footpred.env
vi config/footpred.env          # 填 SPRING_DATASOURCE_PASSWORD 与 MYSQL_PASSWORD
```

> **密码含特殊字符时用单引号包住**，例如 `MYSQL_PASSWORD='p@ss!word'`，
> 否则 `!` 会触发 bash 历史展开。

### 1.7 检查状态

```bash
bash scripts/12-status.sh
```

全绿就可以进行下一步。有红色项按提示处理。

### 1.8 启动后端

```bash
bash scripts/10-start-backend.sh --daemon
```

### 1.9 主机侧验证

浏览器打开：
```
http://<VM_IP>:8080/api/catalog/jobs
```

看到 19 个作业命令的 JSON 就成功了。

或用脚本验证：
```bash
# 在主机 Git Bash 里
bash deploy/scripts/40-host-check.sh
```

---

## 2. 固定虚拟机 IP（强烈建议，一次配置永久解决）

NAT 模式下 IP 由 DHCP 分配，**重启后可能变化**，这正是"每个人 IP 不同"的根源。改成静态 IP 后就不用每次改配置。

```bash
# 在虚拟机里
vi /etc/sysconfig/network-scripts/ifcfg-ens33
```

改成：

```ini
BOOTPROTO=static
ONBOOT=yes
IPADDR=192.168.86.130
NETMASK=255.255.255.0
GATEWAY=192.168.86.2
DNS1=192.168.86.2
```

> - `GATEWAY` 必须是 **192.168.86.2**（VMware NAT 网关），不是 `.1`
> - `.1` 是主机侧的 VMnet8 网卡
> - `IPADDR` 选 128–254 之间，且不要和别人重复

然后：

```bash
systemctl restart network
ip addr show ens33 | grep inet
```

如果 IP 冲突或连不上，改 `IPADDR` 最后一段再试。

---

## 3. 日常运行流程

每次演示/测试：

```bash
cd /job/footpred

# 1) 看状态
bash scripts/12-status.sh

# 2) 跑演示作业（自动生成新 run-id）
bash scripts/30-run-demo.sh

# 3) 导出结果给后端
bash scripts/20-export-results.sh

# 4) 导入 MySQL（仅启用 MySQL 时需要）
bash scripts/22-import-results.sh

# 5) 重启后端加载新数据
bash scripts/11-stop-backend.sh
bash scripts/10-start-backend.sh --daemon
```

第 2 步结束后会打印 `RUN_ID`，第 3、4 步要用同一个：

```bash
RUN_ID=20261025-143000 bash scripts/20-export-results.sh
RUN_ID=20261025-143000 bash scripts/22-import-results.sh
```

第 4 步导入后可以查询：

```bash
curl http://127.0.0.1:8080/api/jobs
curl http://127.0.0.1:8080/api/jobs/summary
```

---

## 4. 换一台电脑要改什么

**只改 `config/footpred.env` 一个文件。**

| 场景 | 改哪一项 |
|---|---|
| 虚拟机 IP 变了 | `VM_HOST`、`YARN_RM_HOST` |
| Hadoop 装在别的路径 | `HADOOP_HOME`、`HADOOP_CONF_DIR` |
| JDK 版本/路径不同 | `JAVA_HOME` |
| 端口 8080 被占用 | `BACKEND_PORT` |
| MySQL 密码不同 | `MYSQL_PASSWORD`、`SPRING_DATASOURCE_PASSWORD` |
| 项目放在别的目录 | `VM_PROJECT_ROOT`、`FOOTPRED_LOCAL_ROOT` |
| 想换数据集 | `DATA_FIVE_LEAGUES_HDFS` 等 |

**不用改任何代码、不用重新编译。**

如果连环境路径都不确定，直接：

```bash
bash scripts/00-detect-env.sh --write
```

它会重新探测并覆盖（原文件自动备份为 `.bak.时间戳`）。

---

## 5. 配置项对照表

`config/footpred.env` 里的变量如何生效：

| 变量名 | 谁在读 | 说明 |
|---|---|---|
| `VM_HOST` | shell 脚本 | 主机侧连虚拟机用 |
| `BACKEND_PORT` | 脚本 + Spring | 后端监听端口 |
| `BACKEND_HOST` | 脚本 + Spring | `0.0.0.0` 才能被主机访问 |
| `FOOTPRED_LOCAL_ROOT` | Spring | 后端读结果的根目录 |
| `FOOTPRED_RESULT_*` | Spring | 各类产物的相对路径 |
| `FOOTPRED_DRAW_MARGIN_*` | Spring | 推理阈值，必须与模型配套 |
| `HADOOP_HOME` 等 | shell 脚本 | 作业执行环境 |
| `MYSQL_*` | shell + Spring | 数据库连接 |

**Spring 如何读 shell 变量**：Spring Boot 的 relaxed binding 会把 `FOOTPRED_LOCAL_ROOT` 自动映射到 `footpred.local.root`，所以 `application.properties` 里不用再配一遍。启动脚本里已 `export` 了这些变量。

---

## 6. 故障排查

### 后端启动失败

```bash
# 看日志
tail -50 logs/backend-$(date +%Y%m%d).log

# 常见原因
# 端口占用
ss -tlnp | grep 8080
# Java 版本不对（需 1.8）
java -version
```

### 主机打不开 `http://VM_IP:8080`

按顺序查：

```bash
# ① 虚拟机内能否访问自己
curl http://127.0.0.1:8080/api/catalog/jobs

# ② 防火墙
firewall-cmd --state
firewall-cmd --permanent --add-port=8080/tcp
firewall-cmd --reload

# ③ 监听地址是否为 0.0.0.0
ss -tlnp | grep 8080
# 显示 127.0.0.1:8080 就是错的，改 BACKEND_HOST=0.0.0.0

# ④ 主机能否 ping 通
ping 192.168.86.130
```

### 接口返回空结果

```bash
# 检查导出目录
ls -la /job/footpred/export/
find /job/footpred/export -name '*.tsv' | wc -l

# 若为 0，先导出
bash scripts/20-export-results.sh
```

### 作业失败

```bash
# 看 YARN 日志
yarn logs -applicationId application_xxx

# 或看某个 run 的 stderr
hdfs dfs -cat /footpred/handoff/runs/<run-id>/module2/rf4-predictions-test/../*stderr* 2>/dev/null
```

### "拒绝覆盖已存在的 HDFS 目录"

这是**设计如此**，防止覆盖历史答辩数据。换一个 run-id：

```bash
RUN_ID=demo-$(date +%H%M%S) bash scripts/30-run-demo.sh
```

### 模型特征数不匹配

访问 `/api/models`，看 `featureCountMatches` 是否为 `true`。为 `false` 说明模型与代码版本不配套：

- 俱乐部：73 维，`draw-aware-v3`，draw margin `0.11`
- 国家队：56 维，`international-v1`，draw margin `0.15`
- LR-xG：16 系数

### MySQL 相关

**后端启动时报数据源错误**

```bash
# 确认开关与连接串
grep -E "MYSQL_ENABLED|DATASOURCE" config/footpred.env

# 确认 MySQL 在跑
systemctl status mysqld
ss -tln | grep 3306
```

**`Access denied for user 'footpred'@'localhost'`**

账号没建或密码不对：

```bash
MYSQL_ROOT_PASSWORD=你的密码 bash scripts/21-init-mysql.sh
```

若密码含 `!`、`@`、`#`，在 `footpred.env` 里必须用单引号：

```bash
MYSQL_PASSWORD='p@ss!word'
SPRING_DATASOURCE_PASSWORD='p@ss!word'
```

**`Unknown database 'footpred'`**

```bash
bash scripts/21-init-mysql.sh
```

**导入返回 `status: partial`**

看响应里的 `skipped` 字段，通常是导出文件不存在。检查：

```bash
ls -la export/runs/<run-id>/module2/
ls -la export/runs/<run-id>/module5/
```

**导入后 `/api/jobs` 是空的**

`/api/jobs` 展示的是**作业元数据**（`job_instance`），不是结果数据。
作业元数据由作业提交写入，当前只读层还没有提交功能，所以为空是正常的。
结果数据用 `/api/results/*` 查询，它读文件不读库。

**`/api/jobs` 返回 404**

`FOOTPRED_MYSQL_ENABLED` 不是 `1`，`JobController` 未注册。改配置后需重启后端。

---

## 7. 安全约束（来自交接包，不可违反）

1. **不覆盖历史 run 目录**：脚本检测到已存在会退出，不自动删除
2. **不用 `all` 类命令演示**：`club-all` / `international-all` / `all` 会重新训练
3. **不混用 `/footpred` 与 `/FootPred`**：HDFS 大小写敏感，两套根目录分别属于两个交付包
4. **不改 draw margin 组合**：俱乐部 0.11、国家队 0.15，混用后指标对不上
5. **MySQL 只存结构化结果**：不存原始 CSV/JSON/SequenceFile/模型
6. **不改 `config/handoff.env`**：那是交接包自己的配置，与本文件的 `footpred.env` 是两层

---

## 8. 脚本清单

| 脚本 | 在哪跑 | 作用 |
|---|---|---|
| `00-detect-env.sh` | 虚拟机 | 探测 Java/Hadoop/HBase/MySQL 路径，生成配置 |
| `10-start-backend.sh` | 虚拟机 | 启动后端（`--daemon` 后台） |
| `11-stop-backend.sh` | 虚拟机 | 停止后端（先 TERM 后 KILL） |
| `12-status.sh` | 虚拟机 | 全量状态检查，排查第一站 |
| `20-export-results.sh` | 虚拟机 | HDFS 结果 → 本地 tsv（`--models` 只导模型） |
| `21-init-mysql.sh` | 虚拟机 | 建库、建表、建应用账号（幂等） |
| `22-import-results.sh` | 虚拟机 | 把 tsv 导入 MySQL（`--dry-run` 只列出） |
| `30-run-demo.sh` | 虚拟机 | 跑演示作业（`--quick` 只跑模块二） |
| `40-host-check.sh` | 主机 Git Bash | 检查主机能否连到虚拟机 |

---

## 9. 一个最小可用的验证序列

新机器上从头到尾只要这 5 条：

```bash
cd /job/footpred
chmod +x scripts/*.sh
bash scripts/00-detect-env.sh --write    # 探测并生成配置
vi config/footpred.env                   # 只改 MySQL 密码
bash scripts/12-status.sh                # 确认全绿
bash scripts/10-start-backend.sh --daemon
```

然后主机浏览器打开 `http://<VM_IP>:8080/api/catalog/jobs`。

**此时即使没跑过任何作业，后端也能正常响应**——`/api/catalog/*` 和 `/api/models` 不依赖作业输出，只有 `/api/results/*` 需要先导出数据。这是有意的设计，方便分阶段验证。
