# FootPred 数据模型

本文件包含两层模型，用途不同：

| 层次 | 内容 | 用途 |
|---|---|---|
| **概念模型** | 只含实体、属性、联系基数，不含字段类型与索引 | 需求沟通、论文设计章节、答辩讲解 |
| **物理模型** | 建表 DDL 的可视化（含 `bigint`/`varchar`、主外键、索引） | 开发实现、数据库建表依据 |

---

# 第一部分 概念模型

![FootPred 概念数据模型](figures/er-conceptual.png)

## 1. 实体说明

| 实体 | 业务含义 | 标识 | 主要属性 |
|---|---|---|---|
| 联赛 | 赛事组织单位 | 联赛编号 | 联赛名称、所属国家、赛事级别 |
| 球队 | 参赛主体 | 球队编号 | 球队名称、所属联赛、主场城市 |
| 球员 | 参赛个体 | 球员编号 | 球员姓名、惯用位置、国籍 |
| 比赛 | 一场对阵 | 比赛编号 | 日期、主客队进球、结果、是否中立场 |
| 比赛预测 | 对比赛的赛前推断 | 预测编号 | 三类概率、双方期望进球、比分矩阵、是否命中 |
| 分析结果 | 历史统计产出 | 结果编号 | 分析类别、统计维度、指标名与值、样本数 |
| 计算作业 | 产生上述结果的计算过程 | 作业编号 | 所属模块、状态、模型版本、执行进度 |

## 2. 联系说明

| 联系 | 类型 | 含义 |
|---|---|---|
| 联赛 — 球队 | 1 : N | 一个联赛包含多支球队，每队归属一个联赛 |
| 球队 — 球员 | 1 : N | 一支球队拥有多名球员 |
| 球队 — 比赛 | 1 : N（两处） | 比赛有主客两方，分别对应主队与客队 |
| 球员 — 比赛 | M : N | 一名球员参加多场比赛，一场比赛有多名球员出场并产生多次射门 |
| 比赛 — 比赛预测 | 1 : N | 一场比赛可有多条预测记录（不同模型、不同版本） |
| 比赛 — 分析结果 | 1 : N | 一场比赛参与多条历史统计 |
| 计算作业 — 预测 / 分析结果 | 1 : N | 一次计算作业产出多条结果 |

## 3. 两个建模决策

**① 为什么「射门事件」「出场记录」不在概念模型中**

它们是**弱实体**——不能独立存在，必须依附于（球员，比赛）这一组合。
在概念模型中，用 `M : N` 的一方"出场 / 射门"承载即可；到了物理模型，才展开为
独立表（`shot`、`appearance`）以便存储明细。

**② 为什么「球队 — 比赛」有两条联系**

主场与客场语义不同，但基数相同（都是 1 : N）。概念模型画两条线表达两个角色，
物理模型中则体现为 `match` 表里的 `home_team_id` 与 `away_team_id` 两个外键。

---

# 第二部分 物理模型

以下内容为数据库实现细节，对应 `deploy/sql/schema.sql`。

## 4. 表结构 ER 图

表结构定义见 `backend/deploy/sql/schema.sql`，完整建表语句 DDL 可直接用于部署。

### 4.1 作业调度核心（4 实体）

### 4.2 结果数据（3 实体 + 作业）

### 4.3 全图

### 4.4 图例

Mermaid ER 图的连线两端符号是标准鸦爪记法：

| 符号 | 含义 |
|---|---|
| `&#124;&#124;` | 恰好一个（1） |
| `o{` | 零个或多个（N） |

`JOB_INSTANCE &#124;&#124;--o{ MATCH_PREDICTION` 读作：
**一次作业提交对应零到多条比赛预测**。

---

## 5. 实体关系说明（物理层）



### 为什么 `job_instance` 是中心实体

所有结果表都用 `run_id` 关联到 `job_instance`，但**没有建外键**。这是有意的：

1. `run_id` 在算法包侧是目录名（`train-20261012-210109`），作业失败或目录被手工清理后，
   结果表可能先于作业记录消失，硬外键会导致导入失败；
2. 导入接口允许"只导结果、不导作业记录"（例如对方只拿到别人的 export 目录，
   没有作业元数据），此时外键会阻止导入。

因此关联是**逻辑关联**，由 `run_id` 字符串承担，应用层保证一致性。

### 唯一的物理外键

`job_attempt.job_instance_id → job_instance.id`，带 `ON DELETE CASCADE`。
理由：attempt 不能脱离 job 存在，删除作业记录时轮次记录应当一并清除。

### 1:N 而非 1:1 的关键点

`job_instance` 与 `yarn_application_id` 是 **1:N**：

- `train` 命令内部是 T 轮 L-BFGS 迭代，**每轮一个独立 YARN application**
- `job_instance` 只保留最后一次的 app id（便于列表展示）
- 完整历史在 `job_attempt` 里逐轮记录

若按"一个作业一个 applicationId"建模，训练进度永远显示不出来。

### 唯一键的设计依据

| 表 | 唯一键 | 为什么这样设计 |
|---|---|---|
| `job_instance` | `(run_id, command)` | 同一个 run 目录下可以有多个命令（predict + evaluate） |
| `match_prediction` | `(run_id, source, match_id)` | 同一 run 下 DC 与 RF 两套预测共存 |
| `evaluation_metric` | `(run_id, module, split, metric_key)` | 模块二与模块三都有 test 指标，需按 module 区分 |
| `player_profile` | `(run_id, player_id, team_name)` | **球员会转会**，只按 player_id 会拒绝合法数据 |
| `season_simulation` | `(run_id, league, team)` | 跨联赛队伍可能重名 |
| `history_point` | 无唯一键 | 长表，同一 run 下多行同维度是正常的 |

`player_profile` 那条尤其关键：reducer 按 `(team, match, player)` 出 key，
一个球员同赛季换队会产生多行，实测 10992 行原始数据合并后为 7547 个 (球员,球队) 组合。

---

## 6. 数据流转（从文件到表）

```mermaid
flowchart LR
    A["原始 CSV<br/>联赛 19763 场<br/>国家队 49945 场"] -->|"hdfs dfs -put"| B["HDFS<br/>/footpred/handoff/input"]
    B -->|"RF0 / prepare"| C["清洗<br/>rf0-clean (SequenceFile)"]
    C -->|"RF1 / P0b"| D["特征<br/>73 维 / training_data"]
    D -->|"RF2"| E["时间切分<br/>train/valid/test/backtest"]
    E -->|"RF4 / predict"| F["预测<br/>part-m-* TSV"]
    F -->|"RF5 / evaluate"| G["评估<br/>key-value"]
    F -->|"20-export-results.sh"| H["本地 export<br/>*.tsv"]
    G -->|"同上"| H
    H -->|"POST /api/admin/import/*"| I["MySQL<br/>7 张表"]
    I -->|"GET /api/results/*"| J["前端 JSON"]
```

三个关键设计点：

1. **HDFS 是唯一计算边界**：所有 MapReduce 输入输出都在 HDFS，服务层不参与计算。
2. **导出是显式一步**：不让后端直连 HDFS，避免 Spring Boot 与 Hadoop 客户端的依赖冲突；
   导出产物同时满足课程"结构化数据"的要求。
3. **导入幂等**：每次导入先删该 run_id 的旧行再批量插入，重复导入不产生重复数据。

---

## 7. 与课程要求的对应关系

| 课程要求 | 本项目实现 |
|---|---|
| "convert the data from file format into structured data" | `match_prediction` 等 7 张表 + JSON 接口 |
| "storing it in database tables, generating JSON files" | MySQL 7 表 + `/api/results/*` |
| "use Hadoop API, HBase API, or Java API to retrieve results from HDFS" | 用 `hdfs dfs -cat` 导出（等价于 Hadoop FileSystem API），未直接用 HBase |

**关于 HBase**：课程提到 HBase，但两份算法交付包都只用 HDFS 文件，未使用 HBase 表。
虚拟机装了 HBase 2.4.18 但项目未调用。如果课程强制要求 HBase，需要额外做一层
HDFS → HBase 的导入，这是当前**唯一与环境相关的缺口**。
