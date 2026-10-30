# FootPred 足球大数据分析平台

基于 Hadoop MapReduce 的足球数据分析平台，包含 **Web 前端 + 服务层后端 + MapReduce 算法层**。

打开浏览器即可看到完整的数据分析工作台：比赛预测、球员 xG、赛季模拟、历史洞察。

---

## 快速开始（30 秒）

**前提**：已安装 Java 8 或 Java 17。

```bash
java -version
```

**第一步：获取 JAR**

成品 JAR 不打进仓库（21 MB 二进制）。两种方式任选：

```bash
# 方式 A：从 Releases 下载 footpred-backend-1.0.0.jar，放到 target/ 目录
# 方式 B：从源码构建（需 Maven）
cd backend && mvn clean package
cp target/footpred-backend-1.0.0.jar ../target/
```

**第二步：启动**

```bash
java -jar .\target\footpred-backend-1.0.0.jar --server.port=8080
```

看到下面这行就表示启动成功：

```
Started FootPredBackendApplication in 1.3 seconds
```


Windows 用户也可以直接双击根目录的 `start.bat`；Linux/macOS 用 `bash start.sh`。
**打开浏览器**：<http://127.0.0.1:8080>

默认展示中文界面与示例数据。右上角可切换「示例数据 / 真实接口」，也可切换「中文 / EN」。

停止服务：在终端按 `Ctrl + C`。

### 可选：启用作业管理（需外部 MySQL）

`start.bat` 是只读模式，数据来自 `data/export` 下的 TSV 文件，**不需要数据库**。
「作业管理」页面在只读模式下返回 404，因为相关接口受
`footpred.mysql.enabled` 控制。

要查看历史作业列表，需要连一台已装 MySQL 的 Linux 机器（课程环境为 CentOS 7 虚拟机），
双击根目录的 `start-with-db.bat`。它会：

1. 从环境变量读取连接参数，缺省值如下表；
2. 先用 TCP 探测数据库端口，连不上就直接给出排查提示，不让 Spring 崩在启动阶段；
3. 带 `--footpred.mysql.enabled=true --footpred.job.enabled=true` 启动。

| 环境变量 | 缺省值 | 说明 |
|---|---|---|
| `FP_DB_HOST` | `192.168.86.135` | 虚拟机 IP，变了就要改 |
| `FP_DB_PORT` | `3306` | |
| `FP_DB_NAME` | `footpred` | |
| `FP_DB_USER` | `footpred` | |
| `FP_DB_PASSWORD` | 课程虚拟机内置口令 | 设置该变量即可覆盖 |

改 IP 不用编辑脚本，在 cmd 里先设变量再启动即可：

```bat
set FP_DB_HOST=192.168.86.140
start-with-db.bat
```

**三个前提**，缺一不可：

1. 虚拟机上 MySQL 已启动（`systemctl status mysqld`）；
2. 已执行过建库建表：`mysql -u root -p < backend/deploy/sql/schema.sql`；
3. 远程账号有库权限。若报 `Table ... doesn't exist` 但表确实存在，
   检查 `SHOW GRANTS FOR 'footpred'@'%'`，需要 `ALL PRIVILEGES ON footpred.*`。

> 注意：`footpred@127.0.0.1` 有库权限不代表 `footpred@%` 也有。从 Windows 连入走的是
> 后者，两者是不同账号。

即使连上数据库，**从前端提交新作业仍会失败**，因为后端要调用 `hadoop jar`，
而 Windows 本机没有 Hadoop 客户端。作业列表可以看，提交请用虚拟机侧。

---

## 目录结构

```
FootPred-hadoop/
├── target/
│   └── footpred-backend-1.0.0.jar   ← 运行前需放入（见「快速开始」）
├── backend/                         ← 后端源码（Spring Boot）
│   ├── src/main/java/               业务代码
│   ├── src/main/resources/
│   │   ├── static/                  前端打包产物（已并入 JAR）
│   │   └── application.properties   配置入口
│   ├── pom.xml
│   └── deploy/                      部署脚本与运维手册
├── frontend/                        ← 前端源码（原生 HTML/CSS/JS，无 npm 依赖）
│   ├── dist/                        已构建的静态文件
│   ├── scripts/                     本地开发服务器与构建脚本
│   └── package.json
├── data/
│   ├── models/                      三个已训练的模型
│   │   ├── module2/club-rf-v4/          俱乐部随机森林（73 维）
│   │   ├── module2/international-rf-v1/ 国家队随机森林（56 维）
│   │   └── module3/player-xg-v1/        LR-xG（16 系数）
│   └── export/                      结果文件（前端「真实接口」模式读取）
├── docs/                            数据字典、E-R 图
├── start.bat                        Windows 启动（只读模式，无需数据库）
├── start-with-db.bat                Windows 启动（连外部 MySQL，启用作业管理）
└── start.sh                         Linux/macOS 启动
```

---

## 前后端如何衔接

### 部署形态：同源，单进程

前端是纯静态文件，构建后放入 `backend/src/main/resources/static/`，打包时进入 JAR 的
`BOOT-INF/classes/static/`。启动后：

```
浏览器 ──HTTP──▶ :8080
                  ├── /              → static/index.html（前端页面）
                  ├── /app.js 等      → static/*（前端资源）
                  └── /api/**         → Spring Boot Controller（JSON）
```

**同源的好处**：不需要配置跨域，不需要额外起 Node 服务，一个 JAR 就是一个完整应用。

### 接口约定

前端所有请求走相对路径 `/api/...`，与后端同源。涉及的 10 个端点：

| 页面 | 接口 |
|---|---|
| 数据总览 / 比赛预测 | `GET /api/results/matches?source=random-forest` |
| 球员分析 | `GET /api/results/players?limit=100` |
| 赛季模拟 | `GET /api/results/simulation?league=Bundesliga` |
| 历史分析 | `GET /api/results/history?analysis=P6.2` |
| 模型评估 | `GET /api/results/evaluation?split=test` |
| 模型管理 | `GET /api/models` |
| 作业中心 | `GET /api/jobs`（需启用 MySQL） |
| 数据总览 | `GET /api/results/real-table`、`GET /api/catalog/jobs` |

### 两种数据模式

| 模式 | 数据来源 | 用途 |
|---|---|---|
| **示例数据**（默认） | 前端内置 `data.js` | 打开就能看到完整界面，不依赖后端数据文件 |
| **真实接口** | 后端读 `data/export/` 下的 TSV | 展示实际模型产出 |

切换方式：页面右上角的模式开关，或 URL 加 `?mode=live` / `?mode=demo`。

**真实模式读取的路径**（在 `backend/src/main/resources/application.properties`）：

```properties
footpred.local.root=data/export

# 以下路径相对 footpred.local.root
footpred.result.module2-evaluation=handoff/models/module2/club-rf-v4
footpred.result.simulation=handoff/models/simulation_leagues_v1_10k.tsv
footpred.result.real-table=handoff/models/real_final_table.tsv
```

模型路径写 `../models/...`，因为它们放在 `data/models/` 而非 `data/export/` 下。

---

## 配置真实数据

`data/export/` 已内置**完整的真实产出**（12 MB，45 个文件），来自真实 Hadoop 集群：

| 内容 | 规模 | 位置 |
|---|---|---|
| 比赛预测（测试集） | 1 752 场，准确率 51.66% | `runs/prep-225732/module2/` |
| 球员 xG 画像 | 10 992 条（合并后 7 547 组） | `runs/m3-092841/module3/` |
| 赛季模拟 | 96 支球队 × 10 000 次 | `handoff/models/` |
| 真实积分榜 | 96 支球队 | `handoff/models/` |
| P6.1 冠军演变 | 55 个赛季 | `runs/m5-154913/` |
| P6.2 主场优势 | 55 个赛季 | `runs/m5-154913/` |
| P6.3 进球时间 | 175 行 | `runs/p63-093438-*.tsv` |
| P6.4 交锋矩阵 | 2 208 对 | `runs/m5-154913/` |
| P6.5 球员生涯 | 19 个赛季（梅西） | `runs/p65-194220/` |
| P6.6 联赛风格 | 440 行（55 赛季 × 8 指标） | `runs/p66-160856/` |
| P6.7 分差分布 | 217 行 | `runs/p67-154719-*.tsv` |
| P6.8 国籍多样性 | 2 251 行 | `runs/p68-154057/` |

切到「真实接口」即可看到这些数据。八项历史分析的文件位置已在配置中逐个指定，
前端切换分析项时自动读取对应文件。

**要加入更多结果**，把 HDFS 导出的 TSV 放进来即可，不用改代码：

```bash
# 比赛预测（11 列：match_id epoch_day season league home away actual predicted p_home p_draw p_away）
data/export/runs/<run-id>/module2/rf4-predictions-test.tsv

# 球员画像（18 列）
data/export/runs/<run-id>/module3/players.tsv

# 历史分析（P6.1–P6.8）
data/export/runs/<run-id>/module5/*.tsv
```

导出命令见 `backend/deploy/RUNBOOK.md`。

---

## 二次开发

### 后端

```bash
cd backend
mvn clean package          # 产物：target/footpred-backend-1.0.0.jar
mvn test                   # 120 个测试
```

改完代码后需要把前端一起打进 JAR。前端产物在 `src/main/resources/static/`，
若已存在则直接用；需要重新构建前端时见下节。

### 前端

```bash
cd frontend
node scripts/serve.mjs     # 开发服务器，默认 5173，代理 /api 到 8080
node scripts/build.mjs     # 重新构建到 dist/
```

无第三方 npm 依赖，不需要 `npm install`。

**构建后同步到后端**：

```bash
cp frontend/dist/* backend/src/main/resources/static/
cd backend && mvn clean package
```

### 一键重打包

```bash
cd backend && mvn clean package -DskipTests
cp target/footpred-backend-1.0.0.jar ../target/
```

---

## 启用作业提交（可选）

默认只读，不提交 MapReduce 作业。要启用需要：

| 依赖 | 用途 |
|---|---|
| MySQL 8.0 | 存作业元数据（7 张表） |
| Hadoop 3.3.6 | 执行 MapReduce |

配置步骤见 `backend/deploy/RUNBOOK.md`。简要：

```bash
# 1. 初始化 MySQL
MYSQL_ROOT_PASSWORD=<你的密码> bash backend/deploy/scripts/21-init-mysql.sh

# 2. 修改配置
#    backend/src/main/resources/application.properties
#    footpred.mysql.enabled=true
#    footpred.job.enabled=true
#    footpred.hadoop.bin=/path/to/hadoop

# 3. 重新打包并启动
```

启用后 `/api/jobs` 与「作业中心」页面才可用。

---

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | 原生 HTML / CSS / JavaScript（ES Module），无框架依赖 |
| 服务层 | Spring Boot 2.6.4，Java 8，JdbcTemplate |
| 存储 | MySQL 8.0（可选，7 张表） |
| 计算 | Hadoop MapReduce 3.3.6（HDFS + YARN） |
| 模型 | Dixon-Coles 泊松模型、随机森林、逻辑回归 xG |

---

## 数据与方法

平台分析 5 个联赛、55 个赛季的比赛数据，共 **109 024 条记录**
（联赛 59 079 场 + 国家队 49 945 场）。

八个分析维度：

| 编号 | 分析 | 产出 |
|---|---|---|
| P6.1 | 冠军演变 | 每赛季冠军与积分 |
| P6.2 | 主场优势 | 各联赛主场胜率趋势 |
| P6.3 | 进球时间分布 | 7 个时段的进球占比 |
| P6.4 | 交锋记录 | 球队两两对战矩阵 |
| P6.5 | 球员生涯曲线 | 逐赛季进球与 xG |
| P6.6 | 联赛风格演化 | 射门、犯规、牌等指标 |
| P6.7 | 分差分布 | 按年代的主客分差 |
| P6.8 | 国籍多样性 | 各联赛外援比例 |

字段说明见 `docs/DATA-DICTIONARY.md`，数据模型见 `docs/ER-DIAGRAM.md`。

---

## 常见问题

**启动报错 `Port 8080 was already in use`**

换端口：`java -jar target/footpred-backend-1.0.0.jar --server.port=8081`

**浏览器打开是 404 或空白**

确认访问的是 `http://127.0.0.1:8080/`（带根路径），而不是 `http://127.0.0.1:8080/index.html` 之外的路径。
检查 JAR 内是否含前端：`jar tf target/footpred-backend-1.0.0.jar | grep static/index.html`

**切到真实接口后数据为空**

检查 `data/export/` 下是否有对应文件，以及启动时的工作目录是否为项目根目录
（`application.properties` 里的路径是相对路径）。

**作业中心显示「作业服务尚未启用」**

这是正常状态——默认未配置 MySQL。需要作业提交功能时按 `backend/deploy/RUNBOOK.md` 启用。

---

## 许可

代码使用 MIT 许可。数据来源与各自许可见 `LICENSE` 文件末尾的说明。
