# FootPred 前端

中文足球分析工作台，使用原生 HTML、CSS 和 JavaScript，运行与构建均无第三方 npm 依赖。

## 直接运行已打包项目

在项目根目录（含 `pom.xml`）打开 PowerShell：

```powershell
java -jar target/footpred-backend-1.0.0.jar --server.port=8080
```

打开 <http://127.0.0.1:8080>。该 JAR 已包含前端和后端，默认展示示例数据。右上角切换「真实接口」后，页面读取同一个服务的 API；实际结果文件位置在设置中配置。关闭服务时在终端按 `Ctrl+C`。

## 本地启动

需要 Node.js 18 或更新版本。在 `frontend` 目录运行：

```powershell
npm run dev
```

打开 <http://127.0.0.1:5173>。开发服务器将 `/api` 请求代理到 `http://127.0.0.1:8080`，可以同时启动现有 Spring Boot 后端。指定其他后端地址或端口：

```powershell
npm run dev -- --backend http://127.0.0.1:8080 --port 5173
```

不需要执行 `npm install`。`npm run preview` 使用相同服务器，也支持上述参数。

## 页面与数据模式

页面右上角提供「中文 / EN」语言切换。首次打开默认中文，不跟随浏览器语言；切换后立即生效并记住选择，刷新和切换页面仍保持该语言。语言切换保留当前筛选、搜索、数据来源及结果路径，接口参数和原始 JSON 不作翻译。

导航包含数据总览、比赛预测、球员分析、赛季模拟、历史分析、模型评估、模型管理和作业中心八个页面。页面支持联赛、赛季与搜索筛选；比赛预测支持随机森林与 Dixon-Coles 来源切换。

示例模式用于查看完整交互和布局，所有数据均明确标记为示例，不会向后端提交作业。直接打开 <http://127.0.0.1:5173/?mode=demo> 可进入示例模式。

真实数据模式通过现有 `/api` 接口读取结果。数据缺失、模型尚未部署、接口连接失败和作业服务未启用会显示对应状态；真实模式不会自动混入示例结果。可以通过界面的数据模式控件切换，也可以使用 `?mode=live`。

右上角设置中可配置结果路径。比赛路径按随机森林、Dixon-Coles 分别保存，评估路径按 Test、Validation、Backtest 分别保存，历史路径按分析类型分别保存，刷新后仍会保留。球员接口必须传入球员输出路径 `location`，历史接口必须传入所选分析对应的结果路径 `location`；路径属于运行后端的机器，可填写后端可读取的绝对路径，或相对 `footpred.local.root` 的路径。仅选择历史分析类型并不会自动定位结果文件。

后端关闭 MySQL 时 `/api/jobs` 返回 404；只有同时开启 `footpred.mysql.enabled` 和 `footpred.job.enabled` 才能提交、取消作业。作业参数来自后端命令目录，执行进度和日志来自作业接口。

## 打包到后端

在 `frontend` 目录执行构建，再到项目根目录打包：

```powershell
npm run build
Set-Location ..
mvn clean package -DskipTests
```

构建会校验 JavaScript 语法及入口资源，并将 `frontend/dist` 复制到 `src/main/resources/static`。启动生成的 Spring Boot JAR 后，直接访问 <http://127.0.0.1:8080> 即可打开前端；页面和 API 使用同一地址。

上面的打包命令跳过后端测试；现有后端部分测试依赖原电脑的外部模型和数据包。前端验证独立使用下面的命令执行。首次构建后端需要 Maven 能下载依赖。

## 验证

在 `frontend` 目录运行：

```powershell
npm run check
npm test
python tests/browser_check.py
```

浏览器检查需要 Python Playwright 和系统 Chrome，并需要提前启动开发服务器。脚本覆盖八页导航、搜索及重置、预测来源切换、CSV 下载、模型详情、设置弹窗、示例和真实模式隔离、示例作业提交保护、作业服务关闭状态，以及 390 × 844 移动端的导航和页面宽度。模拟真实接口的作业检查包括动态参数表单、202 提交内容、终态日志、轮询停止和 429 错误提示；全部 API 请求均被测试响应拦截，不提交或取消任何实际作业。

检查成功后会在项目根目录的 `artifacts/frontend/desktop.png` 和 `artifacts/frontend/mobile.png` 保存截图，并在 `artifacts/frontend/browser-report.json` 保存逐项通过结果。可通过 `python tests/browser_check.py --url http://127.0.0.1:其他端口` 指定预览地址。
