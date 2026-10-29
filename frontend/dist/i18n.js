/** View-only translations. API values, identifiers, file paths and raw JSON stay intact. */
export const LANGUAGE_KEY = 'footpred-language';

export function readLanguage(storage) {
  try { return (storage ?? globalThis.localStorage)?.getItem(LANGUAGE_KEY) === 'en' ? 'en' : 'zh'; }
  catch { return 'zh'; }
}

let language = readLanguage();
export const getLanguage = () => language;
export const getLocale = () => language === 'en' ? 'en-GB' : 'zh-CN';
export function setLanguage(value, storage) {
  language = value === 'en' ? 'en' : 'zh';
  try { (storage ?? globalThis.localStorage)?.setItem(LANGUAGE_KEY, language); } catch { /* The in-memory choice still works. */ }
  return language;
}

export const english = Object.freeze({
  '数据总览': 'Overview', '比赛预测': 'Match predictions', '球员 xG': 'Player xG',
  '赛季模拟': 'Season simulation', '历史分析': 'Historical analysis', '模型评估': 'Model evaluation',
  '模型中心': 'Model registry', '作业管理': 'Job management',
  '从赛场到数据，发现足球的另一种视角。': 'A fresh perspective on football, from the pitch to the numbers.',
  '用概率理解比赛，让每一次判断有据可依。': 'Understand each match through probabilities and evidence.',
  '从预期进球出发，发现球员的真实进攻价值。': 'Explore attacking performance through expected goals.',
  '探索赛季的多种可能，洞察球队的排名前景。': 'Explore possible seasons and each team’s prospects.',
  '跨越赛季，寻找藏在比赛记录里的规律。': 'Find patterns across seasons of football history.',
  '以可复核的指标，衡量每一次预测的表现。': 'Measure prediction performance with verifiable metrics.',
  '查看模型版本、特征与数据的一致性。': 'Check model versions, features and data compatibility.',
  '从提交到结果，掌握每一项分析任务。': 'Follow every analysis task from submission to results.',
  '主导航': 'Main navigation', 'FootPred 首页': 'FootPred home', 'FootPred 工作台': 'FootPred workspace',
  'WORKSPACE / 分析空间': 'WORKSPACE', 'MANAGEMENT / 管理中心': 'MANAGEMENT',
  '工作空间': 'Workspace', '读懂数据，读懂足球。': 'Know the numbers. Know the game.',
  '每一场比赛，都有值得': 'Every match has a story', '被发现的故事。': 'waiting to be discovered.',
  '探索历史数据': 'Explore the history', '打开设置': 'Open settings', '关闭导航': 'Close navigation',
  '打开导航': 'Open navigation', '界面语言': 'Interface language', '切换到中文': 'Switch to Chinese',
  '切换到英文': 'Switch to English', '使用说明': 'Help', '数据连接设置': 'Connection settings',
  '数据来源': 'Data source', '示例数据': 'Sample data', '真实接口': 'Live API',
  '新建作业': 'New job', '导出数据': 'Export data', '刷新': 'Refresh',
  '当前展示示例数据，用于体验界面与交互；不代表实际模型结果。': 'Sample data for exploring the interface. These are not actual model results.',
  '正在读取真实接口': 'Reading the live API', '当前服务': 'Current server',
  '从数据出发，让足球更有洞见。': 'See the game through the numbers.',
  '示例体验模式': 'Sample mode', '接口连接异常': 'Connection error', '真实数据模式': 'Live data mode',
  '正在读取分析数据…': 'Loading analysis data…', '关键指标': 'Key metrics',
  '暂无分析结果': 'No results yet',
  '当前筛选条件下没有可展示的数据。你可以调整筛选条件，或检查数据连接设置。': 'No data matches the current filters. Adjust your filters or check the connection settings.',
  '重置筛选': 'Reset filters', '连接设置': 'Connection settings', '数据暂未连接': 'Data is not connected',
  '请检查后端是否启动、接口地址与结果文件配置。你也可以在右上角切换到示例数据体验完整界面。': 'Check the backend, API address and result file settings. You can also switch to sample data at the top right.',
  '全部联赛': 'All leagues', '全部赛季': 'All seasons', '全部位置': 'All positions',
  '筛选联赛': 'Filter by league', '筛选赛季': 'Filter by season', '筛选位置': 'Filter by position',
  '搜索球队、球员或关键词…': 'Search teams, players or keywords…', '搜索数据': 'Search data',
  '上一页': 'Previous page', '下一页': 'Next page', '共': 'Total', '条 · 第': 'records · Page',
  '条记录': 'records', '场比赛': 'matches', '场预测结果': 'predictions', '支球队': 'teams',
  '记录。': 'records.', '条': 'records', '页': '', '个': '',
  '未提供联赛': 'League unavailable', '未提供日期': 'Date unavailable', '未提供': 'Not provided',
  '未知': 'Unknown', '主胜': 'Home win', '平局': 'Draw', '客胜': 'Away win',
  '主胜 H': 'Home H', '平局 D': 'Draw D', '客胜 A': 'Away A',
  '主队': 'Home team', '客队': 'Away team', '平': 'Draw',
  '运行中': 'Running', '等待执行': 'Pending', '提交中': 'Submitting', '已完成': 'Succeeded',
  '失败': 'Failed', '已终止': 'Killed',
  '评估比赛数': 'Evaluation matches', '当前评估分组 · Test': 'Evaluation split · Test',
  '预测准确率': 'Prediction accuracy', '胜平负分类表现': 'Win / draw / loss classification',
  '有效模型': 'Available models', '已读取且可用的模型': 'Model files loaded successfully',
  '覆盖联赛': 'Leagues covered', '当前比赛预测结果': 'Current match prediction results',
  '让每一次预测，': 'Give every prediction', '都有': 'the power of ', '数据': 'data', '支撑。': '.',
  '比赛胜负、球员表现、赛季走向。': 'Match outcomes. Player performance. Season prospects.',
  '用模型探索绿茵场上的无限可能。': 'Explore the possibilities with predictive models.',
  '探索比赛预测': 'Explore predictions', 'SAMPLE MATCH · 示例对阵': 'SAMPLE MATCH',
  '等待比赛数据': 'Awaiting match data', '联赛预测表现': 'Prediction performance by league',
  '当前比赛结果 · 按联赛统计': 'Current predictions, grouped by league',
  '准确率': 'Accuracy', '比赛数': 'Matches', '预测比赛数': 'Predicted matches',
  '示例比赛子集': 'Sample match subset', '已读取的比赛结果': 'Loaded match results',
  '五大联赛': 'The big five leagues', '以每条比赛的实际标签核对': 'Checked against actual match labels',
  '查看模型评估': 'View model evaluation', '比赛预测速览': 'Prediction snapshot',
  '概率背后，是对比赛更深的理解': 'A closer look at the game behind the probabilities',
  '查看全部': 'View all', '比赛对阵': 'Fixture', '联赛': 'League', '预测': 'Prediction',
  '胜平负概率': 'Outcome probabilities', '暂无比赛预测': 'No match predictions yet',
  '接入比赛结果文件后，这里会展示预测概率。': 'Connect match result files to see prediction probabilities here.',
  '球员进攻价值榜': 'Attacking player leaders', '按校正 xG / 90 排序': 'Ranked by adjusted xG / 90',
  '球员数据': 'Player data', '暂无球员画像': 'No player profiles yet',
  '在连接设置中指定球员结果文件位置。': 'Set the player result file in connection settings.',
  '暂无可统计的比赛': 'No matches to evaluate',
  '存在实际赛果的比赛结果才能用于计算准确率。': 'Matches need known outcomes to calculate accuracy.',
  '按联赛比较': ' by league',
  '随机森林 · 历史回测': 'Random forest · Backtest', 'Dixon-Coles · 对阵预测': 'Dixon-Coles · Fixtures',
  '预测来源': 'Prediction source',
  'Dixon-Coles 结果提供预期进球与比分概率；原始文件未包含联赛、赛季和比赛日期。': 'Dixon-Coles results include expected goals and score probabilities. The source does not include league, season or match dates.',
  '当前比赛': 'Matches shown', '符合当前筛选条件': 'Matching the current filters',
  '平均主队 xG': 'Average home xG', '回测准确率': 'Backtest accuracy',
  '对阵主队预期进球': 'Expected goals for the home team', '由实际标签重新计算': 'Recalculated from actual labels',
  '平均主胜概率': 'Average home win probability', '当前展示的比赛': 'For the matches shown',
  '平均客队 xG': 'Average away xG', '已知赛果': 'Known outcomes',
  '对阵客队预期进球': 'Expected goals for the away team', '可用于评估的比赛': 'Matches available for evaluation',
  '对阵预测结果': 'Fixture predictions', '比赛预测结果': 'Match predictions',
  '联赛 / 赛季': 'League / season', '主胜 / 平 / 客胜': 'Home / Draw / Away',
  '预期进球': 'Expected goals', '预测 / 实际': 'Predicted / actual', '详情': 'Details',
  '对阵详情': 'fixture details', '查看': 'View', '未找到比赛': 'No matches found',
  '请调整搜索或筛选条件；真实模式下还需确认预测结果文件已经接入。': 'Adjust your search or filters. In live mode, also check that prediction result files are connected.',
  '前锋 FW': 'Forward FW', '中场 MF': 'Midfielder MF', '后卫 DF': 'Defender DF', '门将 GK': 'Goalkeeper GK',
  '球员画像': 'Player profiles', '当前筛选结果': 'Current filtered records',
  '平均校正 xG / 90': 'Average adjusted xG / 90', '考虑样本量的进攻表现': 'Attacking performance adjusted for sample size',
  '累计进球': 'Total goals', '当前展示的球员记录': 'For the player records shown',
  '平均进球概率': 'Average scoring probability', '预计出场至少一球': 'At least one goal in the projected appearance',
  '球员进攻价值排行': 'Attacking player rankings',
  '默认按校正 xG / 90 降序 · 同一球员可能含多场画像记录': 'Default: adjusted xG / 90, descending. Players may have multiple match profiles.',
  '球员 / 球队': 'Player / team', '位置': 'Position', '进球': 'Goals', '累计 xG': 'Total xG',
  '校正 xG / 90': 'Adjusted xG / 90', '进球概率': 'Scoring probability', '出场分钟': 'Minutes played',
  '在连接设置中填写球员结果文件位置，然后刷新。': 'Set the player result file in connection settings, then refresh.',
  '请调整搜索条件，或接入球员 xG 结果文件。': 'Adjust your search or connect player xG results.',
  '夺冠概率前五': 'Top five title probabilities', '所有联赛': 'All leagues',
  '球队之间按概率排序': 'Teams ranked by probability', '赛季，每一种可能': 'Every possible season',
  '通过重复模拟比赛结果，观察球队在不同赛季情景下的积分与最终排名。': 'Repeated match simulations explore team points and final rankings across possible seasons.',
  '夺冠、前列与降级概率来自模拟结果。真实排名与积分用于赛季结束后的对照。': 'Title, top-place and relegation probabilities come from simulations. Actual ranks and points provide a season-end comparison.',
  '示例仅展示部分球队，概率用于界面体验。': 'The sample shows selected teams. Probabilities are for exploring the interface.',
  '模拟轮次与前列名额以实际产出设置为准。': 'Simulation counts and top-place cutoffs follow the actual output settings.',
  '球队赛季前景': 'Team season prospects', '球队': 'Team', '平均积分': 'Average points',
  '平均排名': 'Average rank', '夺冠概率': 'Title probability', '前列概率': 'Top-place probability',
  '降级概率': 'Relegation probability', '真实排名 / 积分': 'Actual rank / points',
  '暂无模拟结果': 'No simulation results', '请接入赛季模拟结果文件，或调整筛选条件。': 'Connect season simulation results or adjust the filters.',
  '冠军演变': 'Champions over time', '主场优势': 'Home advantage', '进球时间': 'Goal timing',
  '历史交锋': 'Head to head', '球员生涯': 'Player career', '联赛风格': 'League style',
  '比分分布': 'Score distribution', '国籍多样性': 'Nationality diversity',
  '失球': 'Goals conceded', '主场胜率': 'Home win rate', '客场胜率': 'Away win rate',
  '进球占比': 'Goal share', '辅助值': 'Secondary value', '球队 A 胜率': 'Team A win rate',
  '球队 B 胜率': 'Team B win rate', '点球 / xG': 'Penalties / xG', '指标值': 'Metric value',
  '比分占比': 'Score share', '外籍占比': 'Foreign player share', '香农指数 / 出场人数': 'Shannon index / players used',
  '历史分析类型': 'Analysis type', '场均进球': 'Goals per match',
  '取当前筛选结果前': 'First', '项 · 各项口径见下方明细': 'results · See the definitions below',
  '暂无历史分析结果': 'No historical analysis results',
  '每种历史分析需要对应的结果文件。在连接设置中填写当前分析的文件位置。': 'Each analysis needs its own result file. Set the current analysis file in connection settings.',
  '分析结果明细': 'Analysis details', '球队 / 球员': 'Team / player', '分析维度': 'Dimension',
  '国家数 / 阵容人数': 'Countries / squad players', '样本数': 'Samples',
  '评估分组': 'Evaluation split', '测试集 Test': 'Test', '验证集 Validation': 'Validation',
  '回测集 Backtest': 'Backtest', '各分组读取各自的指标文件': 'Each split uses its own metric file',
  '准确率 Accuracy': 'Accuracy', '分类正确的比赛占比': 'Share of correctly classified matches',
  '宏平均 F1': 'Macro F1', '综合衡量三个结果类别': 'Balanced performance across all three outcomes',
  '越低越好': 'Lower is better', '排序概率分数 · 越低越好': 'Ranked probability score · Lower is better',
  '混淆矩阵': 'Confusion matrix', '列：预测结果 · 行：实际结果': 'Columns: predicted · Rows: actual',
  '对角线表示预测与实际结果一致的比赛数': 'Diagonal cells are correctly classified matches',
  '暂无评估指标': 'No evaluation metrics', '请确认当前分组的评估文件已经接入。': 'Check that the selected split’s evaluation file is connected.',
  '评估报告': 'Evaluation report', '来自': 'From', '分组的结果文件': 'split result file',
  '平局判定阈值': 'Draw threshold', '对数损失': 'Log loss', '排序概率分数': 'Ranked probability score',
  '空指标表示尚未读取结果': 'Empty metrics mean no results have been loaded', '完整指标': 'All metrics',
  '模型可用性来自文件读取结果。特征数量与 Schema 校验用于核对模型是否与当前数据匹配。': 'Availability reflects readable model files. Feature counts and schema checks validate compatibility with the data.',
  '球员 xG 模型': 'Player xG model', '俱乐部随机森林': 'Club random forest', '国家队随机森林': 'International random forest',
  '模块二俱乐部随机森林': 'Club random forest', '模块二国家队随机森林': 'International random forest',
  '文件可读': 'Available', '等待接入': 'Not connected',
  '基于射门情境估计进球概率，生成球员进攻画像。': 'Estimate scoring probabilities from shot context to build player profiles.',
  '基于赛前特征，预测俱乐部比赛的胜平负概率。': 'Predict club match outcomes from pre-match features.',
  '面向国家队比赛，使用独立的特征集合进行预测。': 'Predict international matches with a dedicated feature set.',
  '模型版本': 'Model version', '特征数量': 'Feature count', '预期特征': 'Expected features',
  'Schema 校验': 'Schema check', '未读取': 'Not loaded', '通过': 'Passed', '需检查': 'Check required',
  '系数数量': 'Coefficients', '决策树数量': 'Decision trees', '模型详情': 'Model details',
  '当前记录': 'Records loaded', '已加载的作业记录': 'Jobs in the current list',
  '当前列表内的运行作业': 'Running jobs in the loaded list', '正常完成的作业': 'Successfully completed jobs',
  '失败作业': 'Failed jobs', '需要进一步检查': 'Jobs requiring attention',
  '这里是示例作业记录。提交与终止操作仅在真实接口模式执行。': 'These are sample job records. Submit and stop actions are available only in live mode.',
  '作业列表': 'Jobs', '作业 / 运行标识': 'Job / run ID', '分析模块': 'Module',
  '状态': 'Status', '开始时间': 'Started at', '比分预测': 'Score prediction',
  '作业服务尚未启用': 'Job service is not enabled', '暂无作业记录': 'No job records',
  '开启后端 MySQL 元数据服务后，可在这里查看和管理作业。': 'Enable the backend MySQL metadata service to view and manage jobs here.',
  '新建一次分析作业，或调整搜索条件。': 'Create an analysis job or adjust your search.',
  '关闭': 'Close', '取消': 'Cancel', '保存并连接': 'Save and connect',
  '选择数据来源。真实模式只展示后端返回的数据；连接失败与缺失结果会单独显示。': 'Choose a data source. Live mode shows only backend responses, with explicit connection and missing-result states.',
  '示例数据 · 体验界面': 'Sample data · Explore the interface', '真实接口 · 接入后端': 'Live API · Connect the backend',
  '后端地址': 'Backend address', '留空，使用当前服务 /api': 'Leave blank to use this server’s /api',
  '随 Spring Boot 启动或使用开发代理时留空。跨域地址需要后端允许该来源。': 'Leave blank when using Spring Boot or the development proxy. Other origins require backend CORS support.',
  '结果文件位置': 'Result file locations', '结果文件路径': 'Result file path',
  '填写后端可读取的文件或目录。比赛、评估、历史路径分别保存到当前来源、分组、分析类型。': 'Use files or directories readable by the backend. Match, evaluation and history paths are saved per source, split and analysis.',
  '随机森林': 'Random forest', '仅应用于': 'Applies only to', '；切换分析后可分别配置。': '. Configure each analysis separately.',
  '仅应用于当前预测来源；两种模型的输出目录可分别配置。': 'Applies to the current prediction source. Each model can use a separate output directory.',
  '仅应用于当前评估分组；留空使用后端默认配置。': 'Applies to the current evaluation split. Leave blank to use backend defaults.',
  '请填写后端本地结果目录或文件路径。': 'Enter a local result directory or file path on the backend.',
  '模型版本、特征 Schema 与参数详情。': 'Model version, feature schema and parameters.',
  '以下来自示例数据。': 'The following is sample data.', '读取状态': 'Availability', '版本': 'Version',
  '特征 Schema': 'Feature schema', '特征数': 'Features', '比赛预测详情': 'Match prediction details',
  '比赛日期': 'Match date', '赛季': 'Season', '预测结果': 'Predicted result', '实际结果': 'Actual result',
  '主队预期进球': 'Home expected goals', '客队预期进球': 'Away expected goals', '高概率比分': 'Top scorelines',
  '示例模式不提交作业。请切换到真实接口后创建。': 'Sample mode does not submit jobs. Switch to the live API to create one.',
  '作业服务尚未启用，请先开启后端 MySQL 与作业提交。': 'Enable backend MySQL and job submission before creating a job.',
  '暂无可提交的作业目录。': 'No job commands are available.', '新建分析作业': 'Create an analysis job',
  '参数来自后端作业目录。提交后可在详情中查看执行状态与日志。': 'Parameters come from the backend command catalog. View status and logs after submission.',
  '选择作业': 'Job command', '运行标识（可选）': 'Run ID (optional)', '留空由后端生成': 'Leave blank to generate automatically',
  '提交作业': 'Submit job', '作业详情 · 示例': 'Job details · Sample', '作业详情': 'Job details',
  '示例记录不执行实际作业，不产生实时进度。': 'Sample records do not execute jobs or report live progress.',
  '正在读取作业信息…': 'Loading job details…', '作业': 'Job', '运行标识': 'Run ID',
  '等待分配': 'Awaiting allocation', '执行轮次': 'Attempts', '进度': 'Progress', '等待上报': 'Awaiting update',
  '输入位置': 'Input location', '输出位置': 'Output location', '轮次': 'Attempt',
  '执行日志': 'Execution log', '当前没有可展示的日志。': 'No log output is available.', '终止作业': 'Stop job',
  '请等待数据加载完成后导出。': 'Wait for the data to load before exporting.',
  '当前没有可导出的记录。': 'There are no records to export.', '已导出': 'Exported', '示例': 'sample',
  '数据已刷新。': 'Data refreshed.', '完整评估指标': 'All evaluation metrics',
  '已发送终止信号': 'Stop signal sent', '作业不在运行中': 'The job is not running',
  '使用 FootPred': 'Using FootPred', '从分析问题出发，逐步探索数据。': 'Start with a question and explore the data.',
  '在「比赛预测」中查看胜平负与比分概率。': 'View outcome and score probabilities in Match predictions.',
  '在「球员 xG」中比较球员的进攻表现。': 'Compare attacking performance in Player xG.',
  '在「赛季模拟」中探索积分与排名前景。': 'Explore projected points and ranks in Season simulation.',
  '在「历史分析」中切换八类分析视角。': 'Explore eight perspectives in Historical analysis.',
  '使用右上角「真实接口」接入实际后端。': 'Select Live API at the top right to connect your backend.',
  '点击设置，为球员和历史分析指定结果文件。': 'Open settings to configure player and historical result files.',
  '示例数据用于体验。真实数据模式始终展示实际接口结果，不会自动替换为示例。': 'Sample data is for exploration. Live mode always displays API results and never substitutes samples.',
  '请输入 HTTP(S) 地址或相对路径。': 'Enter an HTTP(S) address or a relative path.',
  '数据连接设置已保存。': 'Connection settings saved.', '作业已提交。': 'Job submitted.',
  '英超': 'Premier League', '西甲': 'La Liga', '德甲': 'Bundesliga', '意甲': 'Serie A', '法甲': 'Ligue 1',
  '准备训练数据': 'Prepare training data', '训练 Dixon-Coles 模型': 'Train a Dixon-Coles model',
  '评估比分预测': 'Evaluate score predictions', '基准模型对比': 'Compare baseline models',
  '预测比赛比分': 'Predict match scorelines', '蒙特卡洛赛季模拟': 'Monte Carlo season simulation',
  '球员生涯曲线': 'Player career curve', '联赛风格演变': 'League style over time',
  '比分差分布': 'Goal difference distribution', '国籍多样性分析': 'Nationality diversity analysis',
  '随机森林胜平负预测': 'Random forest outcome prediction', '胜平负模型评估': 'Evaluate match outcomes',
  '清洗五大联赛数据': 'Clean five-league match data', '执行胜平负预测流水线': 'Run the outcome prediction pipeline',
  '球员 xG 预测': 'Predict player xG', '冠军演变分析': 'Analyze champions over time',
  '主场优势分析': 'Analyze home advantage', '历史交锋分析': 'Analyze head-to-head results',
  '进球时间分析': 'Analyze goal timing', '示例模型': 'Sample model',
  '作业服务未启用，请在后端启用 MySQL 作业记录后连接。': 'Job service is disabled. Enable backend MySQL job metadata, then reconnect.',
  '作业服务未启用（HTTP 404）': 'Job service is disabled (HTTP 404)',
  '真实积分榜的读取位置由后端配置决定。': 'The actual table’s file location is configured on the backend.',
  '作业状态暂不可用：': 'Job status is temporarily unavailable: ',
  'API 路径必须以 /api/ 开头': 'API paths must start with /api/',
  '后端地址应为 HTTP(S) 地址或相对路径': 'The backend address must be HTTP(S) or a relative path',
  'API 返回了网页。请检查后端地址或预览服务的 /api 代理配置。': 'The API returned a web page. Check the backend address or the preview server’s /api proxy.',
  '接口未返回有效 JSON': 'The API did not return valid JSON', '请求失败': 'Request failed',
  '请求已取消': 'Request cancelled', '请求超时，请检查后端连接': 'Request timed out. Check the backend connection.',
  '连接后端失败，请检查服务地址及网络': 'Could not connect to the backend. Check its address and your network.',
  '后端尚未启动。可切换到示例数据，或启动 Spring Boot 后刷新。': 'The backend is not running. Switch to sample data, or start Spring Boot and refresh.',
  '作业提交未启用': 'Job submission is disabled', '作业控制未启用': 'Job control is disabled',
  '该作业不在运行中': 'The job is not running', '必填，固定为': 'Required; must be',
  '未知命令：': 'Unknown command: ', '未配置': 'Not configured: ', '对应的 JAR 路径': 'JAR path for this command',
  '已达并发上限': 'Concurrent job limit reached:',
  '（单节点 2 核机器不建议并行），请等待当前作业完成': ' (a single 2-core node should run one job at a time). Wait for the current job to finish.',
  '未知页面：': 'Unknown page: ', '未知历史分析类型': 'Unknown historical analysis type',
  '跳至主要内容': 'Skip to main content', '正在准备你的数据工作台…': 'Preparing your workspace…',
  '足球数据分析': 'Football analytics',
});

const escapeRegExp = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const phrases = new RegExp(Object.keys(english).sort((a,b) => b.length-a.length).map(escapeRegExp).join('|'), 'g');

/** Translate only presentation copy. Never pass API query values through this function. */
export function translateText(value, selectedLanguage = language) {
  const original = String(value ?? '');
  if (selectedLanguage !== 'en' || !/[\u3400-\u9fff]/u.test(original)) return original;
  const trimmed = original.trim();
  if (Object.hasOwn(english, trimmed)) return original.replace(trimmed, english[trimmed]);
  const page = trimmed.match(/^第\s*(\d+)\s*页$/u);
  if (page) return original.replace(trimmed, `Page ${page[1]}`);
  const recordCount = trimmed.match(/^共\s*([\d,]+)\s*条\s*·\s*第\s*(\d+)\s*\/\s*(\d+)\s*页$/u);
  if (recordCount) return `${recordCount[1]} records · Page ${recordCount[2]} / ${recordCount[3]}`;
  const exported = trimmed.match(/^已导出\s*([\d,]+)\s*条(示例)?记录。$/u);
  if (exported) return `Exported ${exported[1]} ${exported[2] ? 'sample ' : ''}records.`;
  const jobDetails = trimmed.match(/^查看作业\s*(.+?)\s*详情$/u);
  if (jobDetails) return `View job ${jobDetails[1]} details`;
  const matchDetails = trimmed.match(/^查看\s*(.+?)\s*对阵详情$/u);
  if (matchDetails) return `View fixture details: ${matchDetails[1]}`;
  return original.replace(phrases, match => english[match])
    .replace(/，/g, ', ').replace(/：/g, ': ').replace(/；/g, '; ')
    .replace(/（/g, ' (').replace(/）/g, ')').replace(/。/g, '.')
    .replace(/\s+\./g, '.');
}

/**
 * Localize text nodes and user-facing attributes in a detached view template.
 * Input values, data-* attributes, URLs, raw JSON and logs remain byte-for-byte.
 * Translating the canonical Chinese view afresh makes switching fully reversible.
 */
export function localizeHTML(html, selectedLanguage = language) {
  if (selectedLanguage !== 'en') return html;
  const template = document.createElement('template');
  template.innerHTML = html;
  const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  for (const node of nodes) {
    if (!node.parentElement?.closest('pre, code, script, style, [data-i18n-skip]')) {
      node.textContent = translateText(node.textContent, selectedLanguage);
    }
  }
  for (const element of template.content.querySelectorAll('[aria-label], [title], [placeholder], [alt]')) {
    for (const attribute of ['aria-label', 'title', 'placeholder', 'alt']) {
      if (element.hasAttribute(attribute)) element.setAttribute(attribute, translateText(element.getAttribute(attribute), selectedLanguage));
    }
  }
  return template.innerHTML;
}

export function updateDocumentLanguage(pageTitle = '') {
  document.documentElement.lang = language === 'en' ? 'en' : 'zh-CN';
  document.title = `FootPred — ${translateText(pageTitle || '足球数据分析')}`;
  const skip = document.querySelector('.skip-link');
  if (skip) skip.textContent = translateText('跳至主要内容');
  const description = document.querySelector('meta[name="description"]');
  if (description) description.content = language === 'en'
    ? 'FootPred football analytics: match predictions, player xG, season simulations and historical insights.'
    : 'FootPred 足球数据分析工作台：比赛预测、球员 xG、赛季模拟与历史洞察。';
}
