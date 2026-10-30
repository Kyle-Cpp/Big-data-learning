# 网页上传 HDFS、运行 MR 与可选训练

## 已实现及当前状态

Windows 网页选择 CSV / TSV / JSON 文件或文件夹 → Windows Spring Boot 接收 → OpenSSH 把内容写入 Linux HDFS → 上传成功后填入作业参数 → 用户提交 MR → 日志与状态继续使用现有作业管理。

Linux 关闭期间已完成代码、离线自动化测试和构建。真实 SSH、HDFS 上传、YARN 运行需要 Linux 启动后联调，目前不能宣称真实集群端到端验收完成。本机继续保持 upload/job/training 三个开关关闭，已有结果展示照常可用。接口能力开关不代表集群在线。

新增入口：作业管理 → 新建作业 → 上传数据集到 HDFS。文件夹会保留 webkitRelativePath，包括所选顶层目录。单文件默认填入文件路径，多文件填入本次数据集目录；可选“使用整个数据集目录”。上传到 `/footpred/web-uploads/<UUID>/...`，不会覆盖旧文件。某个文件失败时停止，不自动提交 MR；已上传部分保留，页面会提示。

每个文件1字节至64 MiB，按文件逐个上传；不接收ZIP，不在服务器解压。选择的目录应只含CSV/TSV/JSON。大数据集首次迁移仍适合用Linux命令行批量上传，网页入口适合现场新增比赛CSV或较小数据集。

## Linux启动后一次性配置

参考 `backend/deploy/application-cluster.properties.example`，将参数合并进自己的私密配置。不要用示例替换原有MySQL配置。Windows使用OpenSSH客户端；服务进程必须能访问自己的SSH私钥及known_hosts。

1. Windows生成专用密钥（已有合适密钥可复用）：`ssh-keygen -t ed25519`。
2. 将公钥加入Linux对应用户的 `~/.ssh/authorized_keys`，设置 `.ssh` 为700、authorized_keys为600。私钥留在Windows，不放进仓库和交接包。
3. 手工执行 `ssh -i <私钥路径> root@192.168.85.10`，核对并保存主机指纹；再验证 `ssh -o BatchMode=yes -i <私钥路径> root@192.168.85.10 true` 能无需密码返回。后端不弹出密码输入，不关闭主机身份校验。
4. Linux启动HDFS、YARN，确认安全模式已关闭、磁盘有空间。确认 `bash -lc` 环境下 JAVA_HOME 正确，Hadoop/HDFS程序与模块JAR路径均存在。
5. 配置 `footpred.cluster.mode=ssh`、host/user/identity、Hadoop/HDFS绝对路径、Linux模块JAR路径和ResourceManager地址。
6. 保留 `footpred.mysql.enabled=true`，开启 `footpred.upload.enabled=true`、`footpred.job.enabled=true`，重启后端。

后端若以后直接部署到Linux，可改 `footpred.cluster.mode=local`，通过本机Hadoop命令执行，无需SSH。

## 推理演示

- 已清洗的RF特征：上传后选择 `club-predict`，绑定 feature-input，指定同一特征schema的已有model-path和全新prediction-output。
- 五大联赛原始CSV：选择 `club-demo`，上传并保留原有联赛/赛季目录结构，绑定raw-input，填写existing-model与新的work-root。会重跑清洗、时序特征、划分、预测和评估，跳过训练。
- 国家队results.csv：选择 `international-demo`，指定已有国家队模型与新的work-root。
- DC fixtures.tsv：选择 `predict`，绑定fixtures，并按作业参数指定正确的国家队或俱乐部模型。

上传成功仅证明文件已写入HDFS，不能保证任意CSV都符合模型输入。各模块要求的字段、球队命名、日期、目录和模型特征schema必须匹配。StatsBomb模块需要完整关联的matches/events/lineups目录，上传一份任意JSON不能替代这些输入。

MR结束后，新结果保留在各自新输出目录。自动复制到Windows展示目录、自动导入MySQL并切换默认模型不在本次新增功能内；沿用既有导出和导入步骤。网页运行新作业也不会自动改变当前展示的已验证结果。

## 后续训练

支持显式开启 `footpred.job.training.enabled=true`，重启后训练命令才出现在新建作业中。后端同样检查开关，不能绕过网页直接调用训练接口。

- `club-all <raw-input> <work-root>`：五大联赛RF完整清洗、特征、训练、预测、评估。
- `international-all <raw-input> <work-root>`：国家队RF完整流程。
- DC：先对上传的results.csv运行 `prepare`，再运行同一新workdir的 `train`，随后evaluate或predict。DC模型文件路径按Linux Driver要求填写。

训练使用算法JAR默认参数；本次没有开放任意-D超参数编辑器。请使用新的work-root/workdir和模型输出位置，保留当前模型及评估依据。新增RF训练命令要求模块二JAR支持club-all/international-all（交接源码Module2Driver已核对）。本次没有新增模块三xG训练网页入口。

## API与验证

- GET `/api/datasets/capabilities`：上传、作业、训练开关与单文件大小上限。
- POST `/api/datasets`：multipart字段file、可选datasetId、relativePath。成功201；集群失败502；未启用503；非法参数400。
- POST `/api/jobs`：沿用已有作业提交接口；日志、进度沿用原接口。

验证包括SSH参数逐项引用、防目录穿越、上传成功/失败的HTTP语义、默认拒绝训练、显式开启后训练任务的进程与状态链路。集群和MR进程在自动化测试中使用模拟替身，不能代替Linux启动后的真实联调。OpenSSH连接中断不保证YARN应用同时停止；需要紧急终止时应在YARN端确认应用状态。

本次为本地代码变更，尚未推送GitHub。原模块一、三、五修复说明继续适用。
