# FootPred 足球大数据平台

Hadoop MapReduce 足球分析课设的 Web 展示与结果接入项目，包含原生 HTML/CSS/JavaScript 前端、Spring Boot 后端、MySQL 表结构和已导出的真实结果。

五个功能模块：DC 比分与胜平负预测、随机森林三分类、球员 LR-xG、赛季蒙特卡洛模拟、八项历史分析。界面支持中文 / English。

## 1. 从源码启动（Windows / Linux）

本次验证环境：JDK 11、Maven 3.9、Node.js 24、MySQL 8。Java编译目标为8；建议先使用已验证的JDK 11复现。前端无第三方 npm 依赖。构建需联网下载 Maven 依赖。

在项目根目录执行：

```bash
node frontend/scripts/build.mjs
mvn -f backend/pom.xml package
```

Windows：

```bat
start.bat
```

Linux / macOS：

```bash
bash start.sh
```

也可直接从根目录启动：

```bash
java -jar backend/target/footpred-backend-1.0.0.jar
```

访问 [真实结果页面](http://127.0.0.1:8080/?mode=live)。前端内置在后端JAR中，不需要额外启动Node服务。通过 Ctrl+C 停止。换端口：`start.bat --server.port=8081` 或 `PORT=8081 bash start.sh`。

默认关闭MySQL和作业执行，Windows可直接展示仓库自带的已导出结果，不需要开启虚拟机、HDFS或YARN。浏览器的示例模式使用独立样例；真实模式读取实际接口，二者不能混作实验结果。

## 2. 启用本机 MySQL

先创建数据库（使用自己的账号登录MySQL）：

```sql
CREATE DATABASE IF NOT EXISTS footpred CHARACTER SET utf8mb4;
```

复制 `application-local.properties.example` 为 `application-local.properties`，填写自己的MySQL账号密码。启动脚本会自动读取私密配置；私密文件已被Git忽略。

应用会创建七张表。数据库账号需具有该库建表及读写权限。已经使用旧版球员表的电脑，先停止旧后端并按 [模块三迁移说明](docs/MODULE3_FIX_20261030.md) 备份迁移，再重新导入各run的原始结果。P6.5旧数据按 [模块五说明](docs/MODULE5_FIX_20261030.md) 重新导入。

本机曾创建的数据和用户不会通过GitHub自动传给其他电脑。导入接口为 `POST /api/admin/import/...`，详见修复说明。只开启MySQL不会自动把展示接口切换成SQL查询：当前展示GET读取本地TSV，MySQL保存导入结果与作业元数据。

直接启动JAR并读取配置时使用：

```bash
java -jar backend/target/footpred-backend-1.0.0.jar --spring.config.additional-location=file:./application-local.properties
```

## 3. 数据、模型和 MapReduce 边界

- `data/export/`：已导出的真实结果，包括RF测试1752场、球员10992条赛季画像、96队赛季模拟和八项历史分析。
- `data/models/`：已训练模型；模块一增加国家队和俱乐部DC参数。DC页面11条是Linux真实MR作业预测的演示对阵，不是已核验的未来赛程，没有准确率。
- `backend/`：Web服务、解析器、MySQL导入、作业提交适配与测试。
- `frontend/`：前端代码；build同步到 `backend/src/main/resources/static/`。
- `scripts/migrate_player_profiles.py`：旧版球员表迁移，需要Python3和mysql命令行。
- `docs/`：数据字典、ER设计、修复记录与部署边界。

源码仓库不包含12GB StatsBomb原始JSON、完整原始训练数据或全部算法JAR。模型文件属于已训练参数，不能替代原始训练集。StatsBomb资料来源见 [官方开放数据仓库](https://github.com/statsbomb/open-data)，使用其数据需遵循原许可及署名要求。

要提交新的MR预测、分析或训练，需要配置Linux Hadoop、HDFS、YARN、相应算法JAR及输入路径，并启用作业执行。当前仓库中的Web部署与完整算法训练复现是两个层次；已有结果展示已验证，不代表新电脑无需配置就能运行全部MR作业。原算法项目参见 [FootPred_hadoop_mapreduce](https://github.com/PeidongLiu05/FootPred_hadoop_mapreduce)。本仓库未提供通用数据上传后自动重训的完整流程。

## 4. 验证与修复记录

```bash
mvn -f backend/pom.xml test
cd frontend
node --test tests/*.test.mjs
```

本轮后端135项：131通过、4跳过；前端20项通过。测试中的数据库使用H2兼容模式；本机另做了真实MySQL导入和浏览器检查。

- [模块一修复](docs/MODULE1_FIX_20261030.md)：RF/DC来源分离、非法结果防护。
- [真实DC重放](docs/DC_LINUX_REPLAY_20261030.md)：11条实际MR输出与来源。
- [模块三修复](docs/MODULE3_FIX_20261030.md)：正确按赛季、球队、球员保留10992条画像。
- [模块五修复](docs/MODULE5_FIX_20261030.md)：7/8列生涯格式区分、19条结果重导入、小数显示。
- [本次GitHub上传说明](docs/GITHUB_UPDATE_20261030.md)。

构建JAR、日志、本机数据库和密码不入库。成品JAR可单独附在GitHub Release；本仓库使用源码构建。
