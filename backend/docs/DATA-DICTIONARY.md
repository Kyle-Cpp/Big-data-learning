# FootPred 数据字典

对应课程要求 §2「Data Dictionary：为数据文件的所有列设计数据字典，清楚描述每个字段的含义
及可进行的分析类型（去重、最值、分组统计、TopN 等）」。

本项目的原始数据有**两个来源、两套列结构**，因此数据字典分两部分；第三部分是
MapReduce 中间产物与数据库表的字典，用于说明数据从原始文件到可查询结构的流转。

---

## 一、五大联赛赛季 CSV（55 个文件）

来源：football-data.co.uk（经 datasets/football-datasets 分发），PDDL 1.0。
位置：`<root>/<league>/season-<YYYY>.csv`，league ∈ {premier-league, la-liga,
bundesliga, serie-a, ligue-1}，每个联赛 2015/16–2025/26 共 11 个赛季。

**记录数：19 763 场**（55 × 约 380 场/赛季，德甲、法甲 306 场）
**列数：22**

| # | 字段 | 类型 | 含义 | 可做的分析 |
|---|---|---|---|---|
| 0 | `Date` | date (YYYY-MM-DD) | 比赛日期 | 时间序列、赛季内时序、按月/周分组 |
| 1 | `HomeTeam` | string | 主队名（football-data 拼写） | 分组、TopN、主客场胜率 |
| 2 | `AwayTeam` | string | 客队名 | 同上 |
| 3 | `FTHG` | int | 全场主队进球 | 最值、均值、泊松拟合、比分矩阵 |
| 4 | `FTAG` | int | 全场客队进球 | 同上 |
| 5 | `FTR` | char{H,D,A} | 全场结果（主胜/平/客胜） | 三分类标签、胜平负分布、主客场优势 |
| 6 | `HTHG` | int | 半场主队进球 | 半场/全场对比、进球时间趋势 |
| 7 | `HTAG` | int | 半场客队进球 | 同上 |
| 8 | `HTR` | char{H,D,A} | 半场结果 | 半场领先转化率 |
| 9 | `Referee` | string | 主裁姓名（可能含逗号，需引号处理） | 分组统计（判罚尺度）、去重计数 |
| 10 | `HS` | int | 主队射门数 | 均值、射正率、与进球相关性 |
| 11 | `AS` | int | 客队射门数 | 同上 |
| 12 | `HST` | int | 主队射正数 | 射正率 = HST/HS、xG 代理指标 |
| 13 | `AST` | int | 客队射正数 | 同上 |
| 14 | `HF` | int | 主队犯规数 | 分组统计、联赛风格对比 |
| 15 | `AF` | int | 客队犯规数 | 同上 |
| 16 | `HC` | int | 主队角球数 | 均值、角球与进球相关性 |
| 17 | `AC` | int | 客队角球数 | 同上 |
| 18 | `HY` | int | 主队黄牌 | 分组统计、纪律性排名 |
| 19 | `AY` | int | 客队黄牌 | 同上 |
| 20 | `HR` | int | 主队红牌 | 去重计数、红牌影响分析 |
| 21 | `AR` | int | 客队红牌 | 同上 |

### 关键约束（数据质量规则）

这三条是实际处理中发现的，不是教科书规则：

1. **`HST ≤ HS` 且 `AST ≤ AS`** 必须成立。若违反，说明列错位或脏数据。
   本项目早期版本曾把索引写成 11/12/13/14（读成 AS/HST/AST/HF），导致 **92.1% 的行
   违反该约束**——这是靠这条规则才查出来的。
2. **`Referee` 可能含引号内逗号**（如 `"Wiley, A. G."`），朴素 `split(",")` 会把
   一行切出 23 列。必须用支持引号的 CSV 解析，或先合并被拆开的字段。
3. **技术统计列可能为空**（意甲 2005/06 有射门无犯规）。空值必须保留为缺失，
   不能当 0，否则会算出"场均犯规 0 次"的假结论。

### 可做的分析类型汇总

| 分析类型 | 适用字段 | 本项目对应 Job |
|---|---|---|
| 去重 / 计数 | 全部 | RF0 清洗（按 league+season+date+home+away 生成 UUID） |
| 最值 / 分布 | FTHG, FTAG, HS, AS, HF… | P6.7 分差分布 |
| 分组统计 | HomeTeam, AwayTeam, Referee, league+season | P6.1 冠军演变、P6.2 主场优势、P6.6 联赛风格 |
| 时序 | Date | RF1 时序特征（防泄漏）、P6.2 年代趋势 |
| TopN | HomeTeam 按积分 | P6.1 每赛季冠军 |
| 关联 / 交叉 | HomeTeam × AwayTeam | P6.4 H2H 对战矩阵 |
| 机器学习特征 | 全部数值列 | RF1 生成 73 维赛前特征 |

---

## 二、国家队比赛 CSV

来源：martj42/international_results，CC0。
位置：`<root>/results.csv`，**记录数 49 945 场**（1872 年至今），**列数 9**。

| # | 字段 | 类型 | 含义 | 可做的分析 |
|---|---|---|---|---|
| 0 | `date` | date | 比赛日期 | 时序、年代分组（P6.7 按十年） |
| 1 | `home_team` | string | 主队（国家队） | 分组、TopN、Elo 计算 |
| 2 | `away_team` | string | 客队 | 同上 |
| 3 | `home_score` | int | 主队进球（含加时，不含点球） | 最值、泊松参数估计 |
| 4 | `away_score` | int | 客队进球 | 同上 |
| 5 | `tournament` | string | 赛事名（Friendly / FIFA World Cup / 各洲杯赛与预选赛） | 分组、按赛事分层评估（major/qual/regional/friendly） |
| 6 | `city` | string | 举办城市 | 分组、去重计数 |
| 7 | `country` | string | 举办国 | 分组、主客场地理分析 |
| 8 | `neutral` | bool{TRUE,FALSE} | 是否中立场 | 过滤条件（`neutral=TRUE` 不计主场优势） |

### 关键口径

- **比分含加时、不含点球大战**——与源数据一致，跨源比较时必须说明。
- **`neutral=TRUE` 时主场优势项必须置零**，否则世界杯正赛（多数中立场）会被高估。
- 队名存在历史变更（如 Zaire → DR Congo），需 `former_names.csv` 按日期映射。

---

## 三、MapReduce 中间产物

| 产物 | 位置 | 结构 | 说明 |
|---|---|---|---|
| `registry.tsv` | `<workdir>/` | 队名 TAB 索引 TAB 窗口内场次 | P0a 产出，稳定索引 |
| `training_data/part-*` | `<workdir>/` | epochDay, homeIdx, awayIdx, gh, ga, neutral, friendly, split, category | P0b 产出，9 列 |
| `rf0-clean/` | `runs/<id>/module2/` | SequenceFile，key=matchId | 清洗后的比赛 |
| `rf1-features/` | 同上 | TSV，7 列元数据 + 73 维特征 | 赛前特征矩阵 |
| `rf2-split/` | 同上 | train / validation / test / backtest 四个子目录 | 时间切分 |
| `rf4-predictions-*/part-m-*` | 同上 | match_id, epoch_day, season, league, home, away, actual, predicted, p_home, p_draw, p_away | 11 列 |
| `rf5-evaluation-*/part-*` | 同上 | key TAB value（18 行指标） | 含混淆矩阵、分类指标 |
| `simulation/part-*` | `runs/<id>/` | league, team, avgPoints, sdPoints, avgRank, pChampion, pTop, pRelegated, realRank, realPoints, rankHist | 12 列 |
| `p6-3-goal-minutes/part-*` | `runs/<id>/p63/` | competition, season, bucket, count, share, total | 7 个时间段分桶 |

---

## 四、数据库表（结构化结果）

后端把上述产物导入 MySQL，共 7 张表。字段级说明见 `ER-DIAGRAM.md` 的实体定义。

| 表 | 主键 | 业务含义 | 数据来源 |
|---|---|---|---|
| `job_instance` | id | 一次作业提交 | 后端作业调度 |
| `job_attempt` | id | 迭代训练的每一轮 YARN 应用 | 后端进度轮询 |
| `match_prediction` | id | 比赛预测（两套格式统一） | RF4 / DC predict |
| `evaluation_metric` | id | 评估指标 key-value | RF5 / P4.6 |
| `player_profile` | id | 球员 xG 画像 | P4.4 |
| `season_simulation` | id | 赛季模拟概率 | P5.2 |
| `history_point` | id | 八项历史分析长表 | P6.1–P6.8 |

---

## 五、对课程要求的符合性自查

| 要求 | 状态 | 说明 |
|---|---|---|
| 数据文件 > 100 000 条 | ⚠️ **未达标** | 联赛 19 763 + 国家队 49 945 = **69 708 条**，差约 3 万 |
| 数据放在 HDFS | ✅ | `/footpred/handoff/input/` |
| 数据字典覆盖所有列 | ✅ | 本文档：22 + 9 列全覆盖 |
| 说明可做的分析类型 | ✅ | 每列标注，另有汇总表 |
| ≥ 4 个 MapReduce Job | ✅ | 实际 15+ 个 |
| 结果用图表呈现 | ⚠️ 待补 | 后端已有 JSON 接口，前端图表未做 |
| 结果导出为结构化数据 | ✅ | MySQL 7 张表 + JSON 接口 |
| `hadoop jar` 运行 | ✅ | 已在真实集群验证 |
