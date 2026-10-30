# FootPred 算法模块（模块一：Dixon-Coles 国家队比赛预测 + 模块五：历史数据分析）

纯 Hadoop MapReduce 实现的 Dixon-Coles 足球比分预测流水线与历史数据分析作业。
本模块为团队分工中的**算法层**：从原始比赛数据出发，产出训练好的模型文件、
预测结果与历史分析 TSV 报告，全部计算以 MapReduce 作业执行；服务层/前端由
其他成员基于本模块的输出文件构建。

## 1. 覆盖范围与设计决策

| 决策 | 取值 | 说明 |
| --- | --- | --- |
| 赛事范围 | 国家队国际 A 级赛事 | 世界杯/欧洲杯/美洲杯/亚洲杯/非洲杯/预选赛/友谊赛，单一全球模型（跨大洲对阵需要统一参数尺度） |
| 训练窗口 | 2016-01-01 起 | 每队约 71 场信息，参数稳定；远古数据经时间衰减后权重趋零 |
| 时间衰减 | `exp(-xi*days_ago)`，半衰期默认 730 天（可调） | Dixon & Coles (1997) 标准做法 |
| 友谊赛 | 似然降权 0.4（可调） | 实验性质强，但承载跨洲连接，不剔除 |
| 中立场地 | `neutral=TRUE` 时不计主场优势 | 世界杯正赛大部分为中立场 |
| 比分口径 | 含加时、不含点球大战 | 与 martj42 数据源口径一致 |
| 防泄漏 | walk-forward：`--valid-date` 前为 train、其后为 valid | 无随机切分 |
| 队名标准化 | former_names.csv 按日期映射（防御性） | 2016+ 窗口实测 0 命中，逻辑保留 |

## 2. MapReduce 作业清单

| Job | 命令 | 类型 | Mapper | Combiner | Reducer |
| --- | --- | --- | --- | --- | --- |
| P0a 队名注册表 | prepare | 聚合 | TeamRegistryMapper | LongSumReducer | TeamRegistryReducer（1 个 reduce，字典序稳定索引） |
| P0b 训练数据 | prepare | map-only | TrainingDataMapper（含赛事类别列） | - | - |
| P1 DC 梯度 ×T | train | 迭代训练 | DCMapper | VectorSumCombiner | DCGradientReducer（1 个 reduce） |
| 每队有效样本量 | train 尾部自动 | 聚合 | TeamWeightMapper | VectorSumCombiner | TeamWeightReducer |
| 评估（分层） | evaluate | 聚合 | EvalMapper（按赛事类别 key） | VectorSumCombiner | EvalReducer（各类别一行 + all 汇总） |
| Baseline 统计 | baseline | 聚合 | BaselineStatsMapper | VectorSumCombiner | BaselineStatsReducer |
| Baseline 阶梯 | baseline | 聚合 | BaselineEvalMapper | VectorSumCombiner | BaselineEvalReducer |
| P1.5 比分矩阵 | predict | map-only（一次请求可含多场对阵） | ScoreMatrixMapper（直接计算 DC 比分矩阵） | - | - |
| P5.0 赛季清洗切分 | simulate 第1步 | 去重与分流 | SeasonPrepareJob.PrepareMapper | - | PrepareReducer（已赛、无比分赛程、评估真值分开输出） |
| P5.1 截止日积分状态 | simulate 第2步 | 聚合 | PlayedMapper + RosterMapper | SumCombiner | TableReducer |
| P5.3 剩余比赛强度 | simulate 第3步 | map-only | FixtureIntensityJob.IntensityMapper（加载现有 DC 模型） | - | - |
| P5.2 蒙特卡洛赛季模拟 | simulate 第4步 | 并行模拟 | MonteCarloMapper（种子分段 rollout + 局部预聚合） | VectorSumCombiner | MonteCarloReducer（1 个 reduce） |
| P5.4 回放评估 | simulate 第5步 | 多输入关联 | PredictionMapper + TruthMapper | - | EvaluationReducer（积分/排名 MAE、事件 Brier） |
| P6.7 大比分分布 | history p67 | 聚合 | ScoreDiffMapper（路径判 source） | VectorSumCombiner | ScoreDiffReducer（单 reduce 流式按 decade 组刷出） |
| P6.6 联赛风格演化 | history p66 | 聚合 | LeagueStyleMapper（每指标独立有效计数） | VectorSumCombiner | LeagueStyleReducer |
| P6.8a 阵容 JSON 扁平化 | history p68 步骤 1 | map-only | LineupFlattenMapper（WholeFileInputFormat，目录名=league/season） | - | - |
| P6.8b 国籍多样性 | history p68 步骤 2 | 聚合 | NationalityMapper（复合 key league/season/country/playerId） | VectorSumCombiner | NationalityReducer（单 reduce 流式 league-season 汇总） |
| P6.5a 生涯曲线 CSV 档 | history p65 步骤 1 | 聚合 | CareerCsvMapper（按文件名分三源解析） | VectorSumCombiner | CareerReducer |
| P6.5b 生涯曲线 events 档 | history p65 步骤 2（--events 可选） | 聚合 | MessiEventsMapper（WholeFileInputFormat，整场单文件流式统计） | VectorSumCombiner | MessiEventsReducer |

训练迭代的参数广播 + 梯度归约范式：Driver 将当前参数 θ（Base64）写入 Job
Configuration → 各 Mapper 独立计算本地梯度 → Combiner 预聚合 → 单 Reducer 全局求和
→ Driver 读回一行汇总、执行 L-BFGS 更新（含均值中心化投影与 rho 裁剪，可选
Shrinkage 先验惩罚项）→ 下一轮。

## 3. 环境与构建

- 运行环境：Hadoop 3.3.6（CentOS 7 + Java 8/11 验证）
- 构建：`mvn package` → `target/footpred-algo-1.0.0.jar`（shade uber-jar，
  hadoop 为 provided，jackson 已打入）
- 单元测试：`mvn test`（110 个测试；核心数学均以数值微分交叉验证，另含离线端到端
  收敛测试 `OfflineConvergenceTest`）

## 4. 数据准备

从四个上游仓库到本地 MapReduce 输入的统一入口与固定版本见
[`docs/DATA_SOURCES.md`](../docs/DATA_SOURCES.md)。本节以下命令说明 HDFS 上的后续处理。

```bash
# results.csv 来自 martj42/international_results（dataset/international_results/results.csv）
hdfs dfs -mkdir -p /data/international
hdfs dfs -put dataset/international_results/results.csv /data/international/
# 可选：队名更名映射
hdfs dfs -put dataset/international_results/former_names.csv /data/international/
```

## 5. 完整运行流程

```bash
JAR=/root/Documents/FootPred/footpred-algo-1.0.0.jar
WORK=/FootPred/module1/work

# ① 数据准备（P0a + P0b）
hadoop jar $JAR prepare \
  --input /data/international/results.csv \
  --former-names /data/international/former_names.csv \
  --workdir $WORK \
  --start-date 2016-01-01 \
  --valid-date 2025-07-01

# ② 训练（P1 迭代，默认最多 60 次尝试；--sigma2 启用收缩先验）
#    产物归档到 <workdir>/runs/train-<时间戳>/（model.json + train_log.txt
#    + team_weights.tsv），同时刷新 <workdir>/model.json 作为 latest 快照
hadoop jar $JAR train \
  --workdir $WORK \
  --half-life-days 730 \
  --friendly-weight 0.4 \
  --sigma2 0.1 \
  --max-iter 60

# ③ 验证集评估（按赛事类别分层 + all 汇总）
#    产物归档到 <workdir>/runs/eval-<时间戳>/evaluation/，
#    并把所用的模型复制为该目录的 model_used.json
#    模型解析顺序：--model > <workdir>/model.json > 最新的 runs/*/model.json
hadoop jar $JAR evaluate \
  --workdir $WORK \
  --model $WORK/model.json

# ③' 信息阶梯基线对比（B0 均匀 / B1 边际频率 / B2 常数泊松 / DC，分层）
hadoop jar $JAR baseline \
  --workdir $WORK \
  --model $WORK/model.json

# ④ 预测（比分矩阵 + 胜平负概率）
hdfs dfs -put fixtures_demo.tsv $WORK/fixtures.tsv
hadoop jar $JAR predict \
  --model $WORK/model.json \
  --fixtures $WORK/fixtures.tsv \
  --out $WORK/predictions \
  --max-goals 10

# ⑤ 模块四五 Job 流水线（回放模式；面向联赛 results_leagues.csv
#    + clubs 模型。cutoff 前按真实结果累计积分，其后按泊松采样模拟；
#    每队输出夺冠/前K/降级概率 + 排名直方图，并附真实最终榜对照）
hadoop jar $JAR simulate \
  --input /FootPred/data/leagues/results_leagues.csv \
  --model $WORK/runs/leagues_v1/model.json \
  --workdir /FootPred/module4 \
  --season-start 2025-08-01 --cutoff 2026-01-01 --season-end 2026-06-30 \
  --total-sims 10000 --maps 2 --seed 42 \
  --top-places 4 --relegation-places auto \
  --run-id fivejobs-demo-001
```

模块四默认依次提交 P5.0、P5.1、P5.3、P5.2、P5.4 五个独立 Hadoop Job。
四个新 Job 的实现位于 `com.footpred.future`；P5.2 继续使用原有模拟、Combiner
和归约逻辑。Driver 只读取准备好的积分状态及比赛强度、组装小型广播上下文和种子段。
模块四加载模块一的俱乐部 DC 模型，不重新训练。

每次必须使用新的 `--run-id`，已有运行目录会被拒绝，避免覆盖历史结果。
`job_manifest.tsv` 记录五个 Job 的名称、真实 Job ID 和完成状态。
旧单 Job 路径仅用于显式回归对照：添加 `--pipeline legacy`。

产物目录如下：

```text
<workdir>/runs/<run-id>/
  p5-0-season-data/{played,fixtures,truth}/
  p5-1-cutoff-table/
  p5-3-fixture-intensities/
  replay_context.tsv
  seed-segments.tsv
  p5-2-simulation/             原始预测，真实排名/积分两列为0占位
  p5-4-evaluation/             各联赛误差和Brier指标，含simulation/actual分支
  simulation/                 保持原11列消费格式，评估阶段附加真实排名/积分
  real_final_table.tsv         保持原8列数据格式
  job_manifest.tsv
  simulate_log.txt
```

`fixtures` 只包含日期、联赛、双方球队和中立场标记，不含未来比分。
`truth` 只供 P5.4 评估使用；模拟上下文的真实排名和积分数组为0占位。
回放是否严格满足训练数据的时间隔离，还取决于所加载模型的训练窗口是否早于 cutoff。

模块五历史分析（workdir 建议用 `/FootPred/module5`，产物归档到
`<workdir>/runs/<run-id>/<analysis>/`；数据准备见下）：

```bash
# p67 大比分分布（输入即模块一/四现有数据，零额外准备）
hadoop jar $JAR history --analysis p67 \
  --input /FootPred/data/international/results.csv,/FootPred/data/leagues/results_leagues.csv \
  --workdir /FootPred/module5 --run-id p67-v1

# p66 联赛风格演化（输入 = 本地合并的 23→22 列 TSV，scripts/build_leagues_full.ps1）
hadoop jar $JAR history --analysis p66 \
  --input /FootPred/data/leagues/results_leagues_full.tsv \
  --workdir /FootPred/module5 --run-id p66-v1

# p68 球员国籍多样性（输入 = 按 league/season 目录分拣的 lineups JSON，
#     scripts/stage_statsbomb.ps1；内部两步：JSON 扁平化 + 国籍聚合）
hadoop jar $JAR history --analysis p68 \
  --input /FootPred/data/statsbomb/lineups \
  --workdir /FootPred/module5 --run-id p68-v1

# p65 球员生涯曲线（CSV 三源必选；--events 给出梅西场次分拣目录后
#     附加俱乐部逐季曲线，目标球员可用 --player-id 覆盖，默认 5503）
hadoop jar $JAR history --analysis p65 \
  --input /FootPred/data/worldcup/player_appearances.csv,/FootPred/data/worldcup/goals.csv,/FootPred/data/international/goalscorers.csv \
  --events /FootPred/data/statsbomb/events \
  --workdir /FootPred/module5 --run-id p65-v1
```

超参数扫描（两阶段，见 improvement_plan.md 与 `scripts/sweep_stage1.sh`、
`scripts/sweep_stage2.sh`；训练数据无需重新生成）：

- 阶段 1：half-life ∈ {540, 730, 1095} × friendly ∈ {0.3, 0.4, 0.5}，9 组，按
  `category=all` 的 meanRPS 选优
- 阶段 2：固定阶段 1 最优，sigma2 ∈ {0.05, 0.1, 0.2}，3 组，同时检查
  thin-evidence 队是否退出 top 榜

选 meanRPS 最低的模型作为最终模型（RPS 是足球预测的标准评估指标）。

## 6. 产出物与接口契约（给下游/队友）

### 运行目录布局

每次 `train` / `evaluate` / `baseline` 默认按日期时间归档，避免多次实验互相覆盖：

```
$WORK/
├── registry.tsv                 # P0a（prepare 产出，重跑即覆盖，稳定索引）
├── training_data/               # P0b（prepare 产出，重跑即覆盖）
├── model.json                   # latest 快照（train 自动刷新，下游可固定引用）
└── runs/
    ├── train-20261012-210109/   # 一次训练的完整归档
    │   ├── model.json
    │   ├── train_log.txt
    │   └── team_weights.tsv
    └── eval-20261012-223045/    # 一次评估/基线的归档
        ├── evaluation/          # 或 baseline_stats/ + baseline_eval/
        └── model_used.json      # 本次报告所用的模型副本（可追溯）
```

`--run-id <id>` 可显式命名（sweep 脚本用语义 id，如 `hl730_fw0.4`、`sigma2_0.1`）；
`--model-out <path>` 仍可用（指定后不做归档、不刷新快照，传统行为）。
`predict` 的输出目录由 `--out` 控制，默认在模型所在目录下。
每次调用 `predict` 仍提交一个 MapReduce Job；fixtures 文件中的每一场对阵由 mapper 独立计算，输出 `part-m-*`。同一对阵若在输入中出现多次，会分别输出多行；跨 map 任务的输出顺序不保证与输入顺序相同。

### `$WORK/registry.tsv`（P0a 产出）
```
队名 <TAB> 索引 <TAB> 窗口内场次
```

### `$WORK/training_data/part-*`（P0b 产出，TSV）
```
epochDay <TAB> homeIdx <TAB> awayIdx <TAB> gh <TAB> ga <TAB> neutral(0/1) <TAB> friendly(0/1) <TAB> split(train|valid) <TAB> category(major|qual|regional|friendly|other)
```
第 9 列为追加列：此前版本按位置解析 f[0..7] 的作业（DC 梯度等）完全向后兼容。

### `model.json`（train 产出，最终模型契约）
```json
{
  "modelType": "DixonColes",
  "version": 1,
  "scoreConvention": "including-extra-time",
  "nTeams": 295,
  "homeAdv": 0.25,
  "rho": -0.06,
  "refEpochDay": 20639,
  "halfLifeDays": 730,
  "sigma2": 0.1,
  "iterations": 47,
  "finalLL": -12345.6,
  "trainedAt": "2026-10-12T...",
  "teams": [
    {"idx": 0, "name": "Afghanistan", "attack": -0.31, "defense": 0.28},
    ...
  ]
}
```
`sigma2` 仅在启用收缩先验时出现。预测公式（下游可直接实现）：
- `lambdaHome = exp(attack[home] + defense[away] + homeAdv * (1 - neutral))`
- `lambdaAway = exp(attack[away] + defense[home])`
- 比分概率：`P(x,y) ∝ tau(x,y) * Poisson(x; lambdaHome) * Poisson(y; lambdaAway)`，
  tau 为 DC 低分修正（0/1 比分格），截断到 0..maxGoals 后归一化

### `$WORK/predictions/part-*`（predict 产出，TSV）
```
matchKey <TAB> pHome <TAB> pDraw <TAB> pAway <TAB> lambdaHome <TAB> lambdaAway <TAB> topScores
Argentina vs France  0.4123 0.2789 0.3088 1.523 1.102 1-1:0.1102|2-1:0.0951|...
```

### `$WORK/evaluation/part-*`（evaluate 产出，分层，每类别一行 + all 汇总行）
```
category=major matches=.. meanRPS=.. meanLogLoss=.. accuracy=.. avgPredHome/Draw/Away=.. trueHome/Draw/Away=..
category=qual ...
category=regional ...
category=friendly ...
category=other ...
category=all ...
```

### `$WORK/baseline_eval/part-*`（baseline 产出，信息阶梯：每类别 × 4 模型各一行）
```
category=all model=B0-uniform matches=1390 rps=0.2241 logloss=1.0986 accuracy=0.3331
category=all model=B1-marginal matches=1390 rps=0.1900 logloss=0.9301 accuracy=0.4763
category=all model=B2-constpoisson matches=1390 rps=0.1832 logloss=0.8875 accuracy=0.4986
category=all model=DC-full matches=1390 rps=0.1675 logloss=0.8565 accuracy=0.6122
```
（数值为示意；B1/B2 参数来自 train split，防泄漏）

### `<run-dir>/train_log.txt`（train 自动产出，HDFS 留档）
逐轮训练轨迹的精简留档：只包含以 `[footpred]` 开头的行（每轮 NLL/accept/reject、
L-BFGS 历史深度、参数表、team weight 诊断、结束标记），**实时流式写入**——训练中断
也保留已完成轨迹；Hadoop 自身 INFO 噪音不写入。默认路径为本次 run 目录，可用
`--log-file <path>` 覆盖；其他命令（evaluate/baseline/predict）也支持该参数留档。

### `<run-dir>/team_weights.tsv`（train 尾部自动产出，每队一行）
```
队名 <TAB> 有效样本量（时间衰减 × 友谊降权后的 Σw）
```
配合收缩先验的薄证据队诊断使用；训练日志尾部同时打印最薄证据的前 10 队。

### `<run-dir>/simulation/part-*`（simulate 产出，TSV，每队一行）
```
league <TAB> team <TAB> avgPoints <TAB> sdPoints <TAB> avgRank <TAB>
pChampion <TAB> pTop <TAB> pRelegated <TAB> realRank <TAB> realPoints <TAB>
rankHist("1:0.641|2:0.247|...")
```
语义：
- `avgPoints/sdPoints/avgRank`：N 次模拟的最终积分均值/标准差、最终排名均值
- `pChampion`：夺冠概率（最终排名 = 1 的频率）；`pTop`：前 K（默认 4，欧战区，
  `--top-places`）概率；`pRelegated`：降级概率（最后 `relegation-places` 名，
  默认按联赛自动：Bundesliga/Ligue 1 取 2、其余取 3，`--relegation-places` 覆盖）
- `realRank/realPoints`：真实最终榜（模拟 vs 真实对照列，答辩/前端直接可用）
- `rankHist`：最终排名直方图（每档概率，已归一化）

模拟口径：
- 排名决胜链：积分 > 净胜球 > 进球 > 队名字典序（跨联赛统一，文档化口径）
- 采样沿用独立泊松近似，使用 DC 模型给出的双方强度，未应用低比分联合概率修正
- 同一输入、模型、`--seed` 和 `--maps` 可复现（每段随机流：baseSeed + 1000003 × mapIndex）

### `<run-dir>/real_final_table.tsv`（simulate 顺带产出）
```
league <TAB> team <TAB> rank <TAB> points <TAB> played <TAB> goalsFor <TAB> goalsAgainst <TAB> goalDiff
```
真实最终积分榜，供前端与模拟结果并排作图。新流水线输出无表头的8列数据；旧路径含两行#注释。

### `<run-dir>/p5-4-evaluation/part-r-*`

每行 `league<TAB>metric<TAB>value`，指标为 `teams`、`points_mae`、`rank_mae`、
`title_brier`、`top_brier`、`relegation_brier`。MAE 和 Brier 均按该联赛球队取平均；
Brier 使用最终输出中已四舍五入的事件概率。该结果是给定单赛季和 cutoff 的回放结果。

### 模块五 history 产出（`<workdir>/runs/<run-id>/<analysis>/`）

数据准备（本地脚本，一次性）：
- `scripts/build_leagues_full.ps1`：165 个 football-data per-season CSV →
  `results_leagues_full.tsv`（22 列：league season date home away fthg ftag
  hthg htag hs as hst ast hf af hc ac hy ay hr ar referee；源 FTR/HTR 列
  剔除；裁判名引号内逗号已合并；输出 put 到
  `/FootPred/data/leagues/`）
- `scripts/stage_statsbomb.ps1`：lineups 全量 + 目标球员场次 events 按
  `<league-kebab>/<season>/` 目录分拣（目录即元数据，WholeFileInputFormat
  从路径取 league/season，零 join；272 个无索引 lineups（id 3890259–3890564）
  按实测规则归入 bundesliga/2015-2016），put 到
  `/FootPred/data/statsbomb/{lineups,events}`

**p67**（`<run>/p67/part-r-*`，每 decade×bucket 一行）：
```
source(intl|league) <TAB> decade <TAB> bucket(-5..5 主视角分差) <TAB> count <TAB> pct(组内%)
```

**p66**（`<run>/p66/part-r-*`，每 league-season 一行）：
```
league <TAB> season <TAB> nGames <TAB> goalsPG <TAB> halfGoalsPG <TAB> shotsPG
<TAB> sotPG <TAB> foulsPG <TAB> cornersPG <TAB> yellowsPG <TAB> redsPG
```
指标覆盖因数据而异（英超 2000-01 起、西甲/意甲/法甲 2005-06 起、法甲犯规
2007-08 起），无数据单元格输出空字段而非 0；覆盖说明见 `data_assessment.md`。

**p68**（`<run>/p68/`，两份产物）：
- `lineups_flat/`：中间产物，每球员一行（league season matchId teamId
  teamName playerId playerName country played；played = positions 非空，
  country 缺失记 Unknown）
- `nationality/part-r-*`：`D` 行 = 每 (league, season, country) 一行
  （distinct squadPlayers / playedPlayers）；`S` 行 = 每 (league, season)
  一行（nCountries squadPlayers hhi shannon foreignShare）。HHI = Σ份额²
  （越大越集中）、Shannon = -Σp·ln p（越大越多样）、foreignShare =
  非本国籍占比（本国映射仅五大联赛，国家队赛事该列为空）

**p65**（`<run>/p65/`，两份产物）：
- `career/part-r-*`：每 (player, source, season) 一行
  `player <TAB> source(wc|intl) <TAB> season(WC-1970|2022) <TAB> apps <TAB> goals <TAB> pens <TAB> ogs`
  （世界杯出场 1970 起逐届、国家队进球 1916 起逐年；两源 player ID 体系
  独立，前端按名字拼接展示球员）
- `messi_club/part-r-*`（需 --events）：目标球员每俱乐部赛季一行
  `league <TAB> season <TAB> apps <TAB> shots <TAB> goals <TAB> xG <TAB> assists <TAB> penGoals`

WholeFileInputFormat（`common/`）：CombineFileInputFormat 子类，每 record =
完整单文件（key=HDFS 路径，value=全文 UTF-8），小文件按
`FileInputFormat.SPLIT_MAXSIZE`（64MB）打包进少数 split；多级子目录需
`FileInputFormat.setInputDirRecursive(job, true)`（history 内部已设）。

### 预测输入 fixtures 文件格式（TSV）
```
# 注释行以 # 开头
homeTeam <TAB> awayTeam <TAB> neutral(0|1)
```
队名拼写必须与 model.json 中 teams 列表一致（不匹配的行会计数器
`UNKNOWN_TEAM` 并跳过）。

## 7. 关键实现细节

- **数值安全**：线性预测子裁剪到 ±12（λ 上界 ~1.6e5）；τ 下限 1e-10，触发时该场
  τ 导数置零并计入 `BAD_TAU` 计数器（正常训练不触发）；rho 全局裁剪 ±0.15。
- **可识别性**：每步更新后 attack/defense 重新中心化（均值 0），homeAdv 与 rho 自由。
- **优化器**：L-BFGS（两循环递归，历史 8 对，曲率条件 s·y>1e-10）；接受/回溯策略
  ——被拒的试探沿同一方向将步长缩至 1/4 重试，步长崩塌（<1e-9）才重置历史换最速
  下降方向；接受点序列保证 NLL 单调不增。收敛条件 `|dF| <= max(1e-6, 1e-10*|F|)`。
- **收缩先验（penalized DC，Dixon & Coles 1997 §6.3）**：`--sigma2` 启用，
  `F_p = F + (1/(2σ²))·Σ(α²+β²)`，homeAdv/rho 不惩罚；1/σ² 的直观含义是
  "先验投出的有效场次数"（σ²=0.1 ≈ 10 场），对充分观测队几乎无影响、只把薄证据
  队拉回联赛平均水平；惩罚项与数据无关，全部在 Driver 端叠加（MR 层零改动），
  与中心化投影按"先投影再计惩罚"固定顺序配合。
- **计数器**（`hadoop job -history` 或 Web UI 可查）：`PARSED / BAD_LINE /
  SKIPPED_BEFORE_WINDOW / UNKNOWN_TEAM / TRAIN_ROWS / VALID_ROWS /
  TRAIN_MATCHES / BAD_TAU / VALID_MATCHES / FIXTURES / UNKNOWN_TEAM(predict)`。
- **迭代日志**：每轮打印 try 序号、accept/reject、NLL 及增量、|g|∞、步长、L-BFGS
  历史深度、badTau、耗时。

## 8. 单元测试与验证方式

| 测试类 | 验证内容 |
| --- | --- |
| DCtauTest | τ 四分支定义值 + 全部偏导数 vs 中心数值微分 + floor 防御 |
| DCObjectiveTest | 每场对数似然值与 dEtaH/dEtaA/dRho vs 数值微分（覆盖全部 τ 分支、8 种比分、3 个 ρ 值） |
| TimeDecayTest | 半衰期换算、参考日权重、半衰点权重 0.5 |
| DCModelTest | Base64 / JSON 往返、投影中心化、rho 裁剪、flat 视图一致性、参数化 λ 公式 |
| MatchParserTest | CSV 解析（正常/表头/坏日期/坏比分/坏标志/空字段） |
| VectorWritableTest | Writable 序列化往返、逐元素累加 |
| LBFGSTest | 严格凸二次函数收敛到真解、曲率条件拒绝、历史深度上限、reset |
| ShrinkageTest | 惩罚值/梯度定义与数值微分、先验信息量解释、非法 σ² 拒绝 |
| RpsMetricsTest | RPS 手工参考值（完美预测、均匀预测、有序性） |
| CategoryTest | 赛事类别映射（数据集真实赛事名、大小写、未知赛事显式 other） |
| ScoreDiffMapperTest | 分差分桶 clamp、年代标签 |
| LeagueStyleMapperTest | 22 列 TSV 解析（空指标独立跳过、FTR 剔除后的列位、坏行拒绝） |
| NationalityReducerTest | HHI / Shannon / 外援率参考值、五大联赛本国映射 |
| CareerCsvMapperTest | worldcup 列偏移（appearances vs goals 差 1 列）、0/1 与 TRUE/FALSE 标志语义 |
| LineupFlattenMapperTest | kebab 目录名映射、梅西事件抽取（射门/进球/xG/助攻/点球，小 JSON 字面量） |
| FilterTeeStreamTest | 日志 tee：前缀过滤、CRLF、残行不转发、文件流故障降级保护 |
| OfflineConvergenceTest | 端到端离线训练（梯度组装 + Driver 循环镜像）：合成数据恢复攻防排序、主场优势、强度 λ 接近真值、ρ 驻点；收缩先验下拉薄证据队回联赛平均、充分观测队的相对强度与 λ 保持不变 |

## 9. 已知边界

- 比分为含加时口径（与数据源一致）；预测 90 分钟口径需另剥离 goalscorers 的
  minute>90 进球（当前未实现，如需要请提出）。
- 女足不在 results.csv 中（本模型仅男足）。
- 长尾弱队（每年仅几场预选赛）参数方差天然偏大；时间衰减会自动压低其旧场次影响。
- 模型 JSON 是唯一的跨团队契约文件；任何 schema 变更需同步下游。
