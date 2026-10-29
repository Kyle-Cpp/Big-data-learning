# 作业提交与 MySQL 元数据 —— 验证报告

记录实现、真实环境验证结果，以及验证过程中发现并修复的缺陷。

验证环境：CentOS 7.9 虚拟机 `192.168.x.x`（内网），Hadoop 3.3.6 + MySQL 8.0.46，2 核 7.8GB。

---

## 一、自动化测试

`mvn clean test` → **119 个测试全部通过**。

| 测试类 | 数量 | 覆盖内容 |
|---|---:|---|
| `CodeNormalisationTest` | 7 | 赛季 `1516 ↔ 2015-16 ↔ 2015-2016`、联赛名双向 |
| `MatchPredictionParserTest` | 9 | 两套预测格式、表头行防护、缺失字段为 null |
| `EvaluationParserTest` | 6 | 真实 v4 指标、混淆矩阵、分层报告 |
| `HistoryParserTest` | 12 | 八项历史分析、复合 key 剥离、空值不当 0 |
| `PlayerAndSimulationParserTest` | 4 | 球员画像、96 队模拟 |
| `ModelFileParserTest` | 4 | 三个模型的特征数与 Schema 校验 |
| `ResultApiIntegrationTest` | 14 | 只读接口端到端，断言项目已公布的指标 |
| `DeploymentConfigBindingTest` | 3 | 环境变量 → Spring 属性映射 |
| `JobMetadataRepositoryTest` | 9 | 7 张表读写、状态机、逐轮 attempt |
| `ResultImportRepositoryTest` | 16 | 六类结果批量导入、幂等、NaN→NULL |
| `ImportApiIntegrationTest` | 7 | 导入接口 HTTP 契约 |
| `CommandBuilderTest` | 13 | 两套参数风格、`-D` 位置、参数校验 |
| `JobServiceTest` | 9 | 提交→运行→完成全生命周期（桩程序） |
| `JobSubmissionApiTest` | 6 | 提交接口在关闭时的行为 |

---

## 二、真实环境验证（已完成）

### 2.1 MySQL

```sql
-- 7 张表全部创建
evaluation_metric  history_point  job_attempt  job_instance
match_prediction   player_profile season_simulation
```

应用账号 `footpred` 可正常登录与读写。

### 2.2 真实 MapReduce 作业

**直接调用 hadoop jar（club-demo，9 个作业全成功）**

```
RUN_ID = prep-225732
rf0-clean   → 本地球赛清洗
rf1-features → 73 维赛前特征
rf2-split   → 时间切分
rf4-predictions-{validation,test,backtest}  → 各 1752 行
rf5-evaluation-{validation,test,backtest}   → 各 18 行指标
```

产出的评估结果与 `RESULTS_MODULE2_CLUB_V4.md` 公布值**逐位一致**：

```
count              1752
accuracy           0.51655251
macro_f1           0.45769241
log_loss           0.98934115
rps                0.20162505
draw_margin        0.11000000
confusion_actual_H 550,86,100
confusion_actual_D 250,76,111
confusion_actual_A 200,100,279
```

这证明修复后的代码（HS/AS/HST/AST 索引 10/11/12/13）、模型和解码链路完全正确。

**通过 API 提交（作业 id=5）**

```
POST /api/jobs → 202, status=RUNNING, id=5
 8s  RUNNING  progress=-   attempts=1
16s  RUNNING  progress=0   attempts=1
48s  RUNNING  progress=73  attempts=1
56s  SUCCEEDED attempts=1  app=application_1792918996150_0014
```

HDFS 产出：`/footpred/handoff/runs/api-test-011/module2/rf4/`
- 4 个 `part-m-*` 文件，共 **1752 行**
- 文件大小与直接调用 hadoop 的结果**逐字节相同**

### 2.3 MySQL 实际写入

```sql
SELECT COUNT(*) FROM match_prediction WHERE run_id='prep-225732';
-- 1752

-- 五个联赛分布
2024-25  La Liga 380 | Premier League 380 | Serie A 380 | Ligue 1 306 | Bundesliga 306

-- 正确预测数（与 accuracy 0.5166 吻合：905/1752 = 51.66%）
SELECT SUM(is_correct) ...  -- 905
```

赛季已归一化为 `2024-25`（源数据是 `2425`），联赛已归一化为 `Premier League`（源数据是 `premier-league`）。

### 2.4 只读接口（真实数据）

| 接口 | 结果 |
|---|---|
| `/api/models` | club 73 维 `draw-aware-v3` / intl 56 维 `international-v1` / LR-xG 16 系数，`featureCountMatches` 全 true |
| `/api/results/simulation` | 拜仁夺冠 0.9804、均分 83.92、真实排名 1 |
| `/api/results/real-table` | 拜仁第 1 名 89 分 |
| `/api/jobs/summary` | 各状态计数正常 |

---

## 三、验证中发现并修复的缺陷

这些是**只有真实环境才能暴露**的问题，本机测试全部通过但部署后失败。

### 3.1 启动脚本未传 Spring 参数（严重）

`FOOTPRED_MYSQL_ENABLED=1` 作为 shell 变量导出，但 Spring Boot 的 relaxed binding
无法把全大写无点号的环境变量拆成 `footpred.mysql.enabled`，导致：

- `MysqlConfiguration` / `JobController` 等 Bean **静默不注册**
- `/api/jobs` 返回 404，而后端日志无任何错误

**修复**：`10-start-backend.sh` 构造 `APP_ARGS` 数组，显式传 `--footpred.mysql.enabled=...`。

### 3.2 布尔值 `1` 与 `havingValue="true"` 不匹配

即使传了 `--footpred.mysql.enabled=1`，`@ConditionalOnProperty(havingValue="true")`
仍不匹配——它做的是字符串相等比较，`"1" != "true"`。

**修复**：脚本增加归一化函数，把 `1/true/yes/on` 统一转成 `true`。

```bash
bool_flag() { case "${1:-0}" in 1|true|TRUE|yes|on) echo true ;; *) echo false ;; esac; }
```

### 3.3 命令行 `RUN_ID` 被配置文件覆盖

`RUN_ID=xxx bash scripts/20-export-results.sh` 不生效：`_lib.sh` 在 source
`footpred.env` 时，文件里的空 `RUN_ID=` 覆盖了调用方的值，导致导出到错误目录、查到 0 行。

**修复**：source 前先暂存调用方传入的值，source 后再恢复。

### 3.4 `schema-mysql.sql` 含孤立的 `CREATE DATABASE` 续行

早期生成脚本只删了 `CREATE DATABASE` 首行，留下 `DEFAULT CHARACTER SET utf8mb4`
孤立片段，`SchemaBootstrap` 按 `;` 切分后执行它 → 启动失败。

**修复**：改为正则提取全部 `CREATE TABLE` 块重新生成。

### 3.5 导出表头被当成数据行导入

`20-export-results.sh` 给预测 TSV 加了表头（便于 Excel 打开），导入时该行变成一条
"预测记录"，`actual` 列宽度 6 撑爆 `CHAR(1)`：

```
Data truncation: Data too long for column 'actual' at row 1
```

**修复**：`MatchPredictionParser` / `EvaluationParser` 按列名识别并跳过表头，
并新增回归测试 `headerRowFromExportScriptIsNotTreatedAsAPrediction`。

### 3.6 MySQL 下自增主键取不到

`getKeys().get("id")` 在 MySQL 返回 null（H2 正常），导致 `running.put(job.id, ...)`
抛 NPE，提交接口返回 500。

**修复**：id 为 null 时按 `(run_id, command)` 回查兜底。

### 3.7 attempt 计数翻倍

检测到第一个 applicationId 时错误地新建了第 2 个 attempt，正常单次作业显示
`attempts=2`。

**修复**：用 `lastApplicationIdFilled` 标记首个 application 已绑定，直接
`attachApplication` 更新原行，只有 id **变化**（新的优化轮次）才新建行。

### 3.8 Windows 换行符

脚本从 Windows 上传后带 CRLF，bash 报 `$'\r': 未找到命令`。

**修复**：部署时统一 `sed -i 's/\r$//'`。已在上传脚本中固化。

---

## 四、三种运行模式（实测）

| 配置 | 只读接口 | `/api/jobs` | 提交作业 |
|---|---|---|---|
| `MYSQL=0, JOB=0` | ✅ 200 | 404 | 503 |
| `MYSQL=1, JOB=0` | ✅ 200 | ✅ 200 | 503 |
| `MYSQL=1, JOB=1` | ✅ 200 | ✅ 200 | ✅ 202 |

实测模式 1、模式 3 的完整链路。模式 2 由 `JobSubmissionApiTest` 覆盖。

---

## 四点五、数据补充包（2026-10-26）验证

`footpred-data-supplement-20261026.tar.gz`（3.4MB）补齐了此前缺失的模块三与 P6.3 输入。

### 包内容核对

| 项 | 声明 | 实测 |
|---|---|---|
| shots.tsv | 54090 行 / 27 列 | ✅ 一致 |
| appearances.tsv | 79579 行 / 11 列 | ✅ 一致 |
| match-index.tsv | 2167 行 / 7 列 | ✅ 一致 |
| p63 events | 2167 个 JSON | ✅ 一致 |
| 参考统计 | 175 行 | ✅ 一致 |
| 模型 SHA256 | `c15075ac...` | ✅ 与交付包、MODEL_INFO 三方一致 |
| SHA256SUMS | 2184 条 | ✅ 全部通过 |

### 真实作业结果

**模块三 `score`（run_id `m3-092841`）**

```
p4-3-shot-xg          → 射门评分
p4-4-player-predictions → 10992 行
p4-6-evaluation       → 32 条指标
```

指标与报告一致：
```
test.log_loss            0.30786331   （报告 0.3079）
test.brier               0.08999552   （报告 0.0900）
test.goal_rate           0.12789620   （报告 12.79%）
validation.log_loss      0.31631606   （报告 0.3163）
validation.brier         0.09201058   （报告 0.0920）
```

**P6.3 `goal-minutes`（run_id `p63-093438`）**

用 0.98MB 精简快照替代 6.67GB 原始事件，产出 **175 行**，与参考数据逐字节一致
（`0.05454545`、`0.07272727` 等 8 位小数完全吻合）。

### MySQL 最终入库量

| 表 | 行数 |
|---|---:|
| match_prediction | 1752 |
| player_profile | 7547（由 10992 行合并而来） |
| evaluation_metric | 50（MATCH_OUTCOME/test 18 + PLAYER_PREDICTION/test 16 + validation 16） |
| history_point | 175（P6.3） |
| season_simulation | 192 |
| job_instance | 5 |
| job_attempt | 3 |

### 本阶段发现并修复的缺陷

| # | 缺陷 | 原因 | 修复 |
|---|---|---|---|
| 9 | `player_profile` 唯一键拒绝合法数据 | reducer 按 `(team, match, player)` 出key，球员转会或租借导致同 id 多行 | 唯一键改为 `(run_id, player_id, team_name)` |
| 10 | 同队内仍重复 | 同一球员同赛季离开又回归 | 导入时按 `(playerId, team)` 合并，分钟加权 |
| 11 | 球员接口重复展示 | API 层未去重 | `collapseByPlayerAndTeam` 保留分钟最多的一行 |
| 12 | 模块三评估无法导入 | `/run/{runId}` 只认模块二/五路径 | 新增 `/api/admin/import/evaluation/{runId}`，按 `split.metric` 前缀拆分 |
| 13 | 包内 SHA256SUMS 为 CRLF | Windows 打包 | 安装前 `sed -i 's/
$//'` |
| 14 | ZooKeeper 占用 8080 | HBase 依赖 | 后端曾临时改用 8081，用户释放后回到 8080 |

## 五、仍未覆盖的部分

| 项 | 说明 |
|---|---|
| 模块三（球员 xG） | 需要 StatsBomb 12GB 原始数据，不在交付包内，未上传 |
| P6.3 进球时间 | 同上，依赖 StatsBomb events |
| P6.5–P6.8 | 搭档包的历史分析，需要 `results_leagues.csv` 与 StatsBomb 分拣数据 |
| 模块一/四（DC、赛季模拟） | 可用已完成产出的模型与结果，但未跑新的 MapReduce |
| 作业取消 | `POST /api/jobs/{id}/kill` 已实现，未在真实运行中触发 |
| 多机部署 | `JobExecutor` 已抽象，跨机需换 SSH 实现 |

模块二（随机森林）与 P6.1/P6.2/P6.4 的所有依赖均已就绪，可随时执行。

---

## 六、数据补齐后的完整验证（2026-10-28）

此前缺失的两个数据源已到位，八项历史分析全部跑通。

### 新增数据源

| 数据 | 规模 | 用途 |
|---|---|---|
| `results_leagues.csv` | 59 079 场（五大联赛全历史） | 模块一/四、P6.6、P6.7 |
| StatsBomb 全量 | 16 GB / 4235 场 / 24 赛事 | P6.5、P6.8 |

**数据量达标**：联赛 59 079 + 国家队 49 945 = **109 024 条**，超过课程要求的 10 万。

### 八项历史分析实测结果

| 分析 | 行数 | 与 docx 报告对照 |
|---|---:|---|
| P6.1 冠军演变 | 55 | 莱斯特城 2015/16 以 81 分夺冠 ✅ |
| P6.2 主场优势 | 55 | 趋势与报告一致 |
| P6.3 进球时间 | 175 | 与参考数据逐字节一致 ✅ |
| P6.4 H2H 对战 | 2208 | — |
| P6.5 球员生涯 | 19（梅西俱乐部赛季） | **2011/12：50 球、xG 35.65** ✅ |
| P6.6 联赛风格 | 440（55 赛季 × 8 指标） | **意甲犯规 30.4 → 25.4**（报告 30→24）✅ |
| P6.7 分差分布 | 217 | — |
| P6.8 国籍多样性 | 2251 | **英超 2015/16：71 国籍 / 646 球员 / 外援 65.17%**（报告 65.2%）✅ |

### 模块四赛季模拟

用 59 079 场联赛数据 + `leagues_v1` 模型，10 000 次模拟：

| 球队 | 报告值 | 实测值 |
|---|---|---|
| 拜仁 | 0.98 / 均分 83.92 | **0.9804 / 83.92** ✅ |
| 巴萨 | 0.73 | **0.7277** ✅ |
| 巴黎 | 0.88 | **0.8822** ✅ |
| 国米 | 0.71 | **0.7138** ✅ |

### MySQL 最终入库量

| 表 | 行数 |
|---|---:|
| match_prediction | 1752 |
| player_profile | 7547 |
| evaluation_metric | 50 |
| history_point | **5420** |
| season_simulation | 192 |
| job_instance | 5 |

### 本阶段发现并修复的缺陷

| # | 缺陷 | 原因 | 修复 |
|---|---|---|---|
| 15 | StatsBomb lineups 平铺上传导致 P6.8 读不到 | 需要 `league/season` 两级目录 | 用 matches 元数据重建索引并分拣 |
| 16 | P6.5 需要 `goalscorers.csv` 但项目未提供 | 依赖 Fjelstul World Cup 数据库 | 从 StatsBomb 事件提取 11 784 条进球记录 |
| 17 | 测试硬编码本机路径 | 开发时图方便 | 改为 classpath 资源 + 模型缺失时跳过 |
| 18 | ZooKeeper 占用 8080 | HBase 依赖 | 后端改用 8081，配置可切换 |

### 仍未覆盖

| 项 | 说明 |
|---|---|
| P6.5 World Cup 出场/进球 | 需 Fjelstul `player_appearances.csv`、`goals.csv`；当前用 `goalscorers` 源替代，产出 5167 条生涯记录 |
| 前端图表 | 后端接口已备 17 个端点，ECharts 页面未做 |
| HBase | 环境已装 2.4.18，两份算法包均只用 HDFS 文件 |

