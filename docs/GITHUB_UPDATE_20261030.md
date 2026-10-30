# GitHub更新交接（2026-10-30）

此版本汇总本机模块一、三、五的修复，核对模块二评估与模块四结果，并修正前端构建与启动脚本目录。

## 上传方式

提供的 GitHub Ready 压缩包是完整源码快照，不包含 `.git`、私密配置、日志、构建JAR或原始大数据集。解压后将内部文件按相同路径更新到自己的仓库，先审阅已有改动再提交。也可在本机现有工作副本提交：

```bash
git status --short
git add README.md .gitignore start.bat start.sh application-local.properties.example backend frontend data docs scripts algorithms
git diff --cached --stat
git diff --cached --check
git commit -m "Fix DC, player season profiles and career results; document deployment"
git push origin HEAD
```

以上命令由上传者自行执行，本次整理未替你提交或推送远程仓库。不需要上传 `dist/` 压缩包本身；上传其中的源码目录内容。

## 版本内容

- DC独立结果路径，11条真实MR预测、模型和来源记录；拒绝错误格式。
- 球员保留10992条赛季/球队/球员画像；旧表迁移、独立重导入接口和赛季筛选。
- P6.5的7/8列输出正确解析；19条俱乐部结果的赛季、进球、xG和出场次数正确显示。
- 前端build正确同步到backend资源目录，启动脚本读取backend/target生成的JAR，并可加载自己的私密配置。
- 无密码的MySQL配置示例、部署步骤、数据范围与验证说明。

## 新电脑与旧电脑

新电脑按根目录README从源码构建。先验证默认只读展示，再按需启用自己的MySQL。

旧数据库须按 MODULE3_FIX_20261030.md 迁移并重导入球员数据；P6.5按 MODULE5_FIX_20261030.md 重导入。只替换源码或JAR不会自动修好旧数据库里的聚合数据。

真实GET展示接口仍读取仓库中的结果TSV，MySQL负责已导入结果和作业元数据。完整MR重跑需要另外准备Hadoop、算法JAR及输入数据；不能将展示复现与全量训练复现混淆。

## 文件管理

本机的 application-local.properties 是私密配置，不能上传。模板只包含占位符；新电脑应使用自己的账号。

原始StatsBomb/训练数据不入库，模型和小型导出结果保留。JAR在backend/target构建，按需附GitHub Release。

旧ER图和数据字典保留历史快照并增加版本说明；当前物理表结构以schema-mysql.sql为准，尤其球员唯一键已改为run_id、season、team_id、player_id。

## 后续模块四升级

本版本还包含 algorithms/module4 五Job算法源码与赛季回放评估接入，部署和运行边界见 MODULE4_FIVE_JOBS_20261030.md。需要在Hadoop执行主机更新算法JAR；只更新Web源码不能替换远端算法JAR。
