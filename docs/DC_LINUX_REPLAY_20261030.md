# 模块一 Linux预测与本机展示

本次使用已训练DC模型，在192.168.85.10的Hadoop 3.3.6和Java8环境提交两个真实map-only预测Job。没有重新训练模型。

## 作业与输出

批次：`dc-replay-20261030-4093cac9`。

| 模型 | YARN application | 预测数 | 实测命令耗时 |
|---|---|---:|---:|
| 国家队 | application_1793329634322_0001 | 6 | 29.6秒 |
| 俱乐部 | application_1793329634322_0002 | 5 | 17.4秒 |

11场是用于演示的对阵组合，不是真实待赛日程；结果不附真实赛果，不计算这11场的预测准确率。两种模型各自计算后仅按相同TSV格式拼接用于展示，没有融合参数。

HDFS根目录：`/FootPred/module1/runs/dc-replay-20261030-4093cac9`（大小写与搭档算法路径保持一致）。

```text
input/raw/international/results.csv          原始国家队比赛49945场
input/raw/international/former_names.csv     历史队名映射
input/raw/five-leagues/<league>/*.csv         原始五大联赛165个CSV
input/prepared/results_leagues.csv           转换为DC公共格式的59079场
input/fixtures/national.tsv                  国家队演示对阵6场
input/fixtures/clubs.tsv                     俱乐部演示对阵5场
models/model_national.json
models/model_clubs_v1.json
predictions-national/part-m-00000
predictions-clubs/part-m-00000
```

输入文件通过SHA256检查后上传。Linux本地目录为`/data/dc-replay-20261030-4093cac9`，包含算法JAR、源数据、模型、赛程、SHA256清单和`run.sh`。输出使用全新目录，未覆盖既有训练或预测结果。

## 预测命令

```bash
HDFS_RUN=/FootPred/module1/runs/dc-replay-20261030-4093cac9
cd /data/dc-replay-20261030-4093cac9
hadoop jar app/footpred-algo-1.0.0.jar predict \
  --model "$HDFS_RUN/models/model_national.json" \
  --fixtures "$HDFS_RUN/input/fixtures/national.tsv" \
  --out /FootPred/module1/runs/新的批次/predictions-national

hadoop jar app/footpred-algo-1.0.0.jar predict \
  --model "$HDFS_RUN/models/model_clubs_v1.json" \
  --fixtures "$HDFS_RUN/input/fixtures/clubs.tsv" \
  --out /FootPred/module1/runs/新的批次/predictions-clubs
```

再次执行时必须指定新的输出路径。上游Driver会删除已存在的预测输出，所以不要复用归档目录。

## 回传与校验

原HDFS输出已getmerge并回传到本仓库：

`data/export/runs/dc-replay-20261030-4093cac9/partner/`

其中`predictions-national.tsv`有6行，`predictions-clubs.tsv`有5行，`predictions.tsv`合计11行。赛程、Hadoop日志、原始数据HDFS计数与`provenance.json`一并留档。模型另保存在`data/models/module1/`。

独立按模型攻防参数、主场优势、rho、0到10比分截断和归一化重新计算，核对了每场胜平负概率、双方lambda以及热门比分概率。全部在原始输出舍入精度内一致。

上游`ScoreMatrixCalculator`确实只输出4位小数概率和3位小数lambda；因此三项概率之和可能为0.9999或1.0001。后端DC校验容差调整为1.5e-4，保留原始结果，不将它错误判作无效，也不擅自改写概率。NaN、越界值和明显不归一的概率仍然拒绝。

## 本机展示

`footpred.result.matches.dixon-coles`已指向这份真实预测TSV。前端在“比赛预测→Dixon-Coles”显示11场结果，保留演示对阵声明。后端可完全从本机已有结果展示，不要求Linux在线。

MySQL导入使用`/api/admin/import/matches/dc-replay-20261030-4093cac9`并指定`source=dixon-coles`及上述预测文件。结果没有真实标签，因此数据库actual/predicted/is_correct保持NULL。

提示：此配置及结果是本地修改，尚未推送GitHub。原始大数据只上传HDFS，不需要把训练原始数据放进Web仓库。
