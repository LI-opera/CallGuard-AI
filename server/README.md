# CallGuard Risk API

本机开发用 FastAPI 服务：风险接口仍返回中性修正0/unknown/0.0；WP-08 新增独立 POST /v1/explanations，以 DeepSeek 生成通话后解释。PostgreSQL保存审计事实，Redis只做短期幂等、缓存和限流。Android真实来电默认未启用云端请求。

从项目根执行：

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
# 新机器复制.env.example为.env，替换密钥与数据库密码；已有环境保留原凭据。
.\scripts\start-risk-server.ps1 -StartInfrastructure
# 另一个终端
.\scripts\test-risk-server.ps1 -Integration
```

只监听127.0.0.1:8000，Swagger /docs，API POST /v1/risk/analyze。使用Bearer密钥。Windows使用server.run入口，勿直接绕过Selector事件循环启动psycopg异步应用。

不提供内存生产降级；Redis/PostgreSQL不可用时返回错误，客户端保留本地结果。停止容器不删除审计卷。完整契约、文件职责、证据与限制见[WP07报告](../docs/wp07-implementation.md)。

模型工厂、.env 切换、鉴权和真实调用证据见 [WP08报告](../docs/wp08-implementation.md)。模型只在后端运行，手机默认关闭 AI，需要用户确认通话结束；不参与实时拦截。

## 本地文字模拟来电演示

此页面先验证 AI 交互逻辑，假设未来通道能提供来电者语音的转写文本。它不连接 Phone Link、Android 或电话音频，也不修改真实白黑名单。与生产风险 API 分开启动，不需要 Redis/PostgreSQL。

从项目根目录运行：

    .\scripts\start-text-call-demo.ps1

浏览器打开 http://127.0.0.1:8765 。本地脚本只读取项目 .env 中的模型配置，调用现有 server/app/models/factory.py；首次实验可使用 DEEPSEEK_API_KEY。网页最多两轮来电者输入，每次发送会调用一次模型。模型返回回复、意图、置信度、追问/结束及简短依据；服务端严格校验 JSON 后确定模拟动作：正常且置信度至少 0.8 → 模拟允许五分钟内再拨，疑似风险且至少 0.9 → 模拟加入黑名单，其他完成态 → 模拟人工复核。第一轮模型要求追问时只追问一次；第二轮结束。调用失败或无效输出不会修改规则。

页面只监听本机 127.0.0.1:8765。每次“新模拟来电”生成一个 `demo-` 开头的虚构来电者 ID。对话结束后点击“模拟同一来电者重拨”，会按本次结果显示模拟响铃、模拟拦截或待复核，并显示事件时间线。五分钟放行按服务端单调时钟过期；模拟黑名单持续到演示服务重启。重拨不调用模型。当前规则、会话和事件只存在服务进程内存，服务重启后清空；网页重置会换一个 ID。最多保存 100 个演示会话，达到上限时重启演示服务即可清空。

输入和对话不写入磁盘，也不上传真实号码。用户输入的文字会发往配置的模型提供方，请勿输入真实号码、验证码或其他敏感信息。模型调用可能计费；失败后不自动重试，状态保留为待复核。

主要文件：server/demo/schema.py（严格请求/输出契约）、server/demo/logic.py（模型对话与确定性模拟动作）、server/demo/ledger.py（内存会话、虚构来电者规则与事件）、server/demo/app.py（独立本地 API）、server/demo/index.html（网页）、server/run_demo.py 与 scripts/start-text-call-demo.ps1（本机启动入口）、server/tests/test_text_call_demo.py 和 server/tests/test_demo_decision_loop.py（交互与边界测试）。

验证命令：

    .\.venv\Scripts\python.exe -m pytest server\tests -q --basetemp=.tmp\pytest-server-with-demo

此演示的“模拟加入黑名单”和“模拟允许再拨”只作用于本机演示服务的虚构 ID，没有 Android 同步或真实电话生效证据。Phone Link 电脑端人工接听已验证，但程序化音频输入、输出及自动接听仍待验证。

## 30 份虚构来电与判断结构调整

样本在 server/demo/evaluation/cases.json：推销10、同事5、老朋友5、家人10。网页样本选择器可载入开场白和必要的补充句。原始基线在 docs/demo-evidence/baseline-20260926.jsonl（30份、53次模型请求），优化后定向复测在 docs/demo-evidence/targeted-v2-20260926.jsonl（7份、7次请求）；可读的逐段问答见 docs/demo-evidence/text-call-evaluation-20260926.md。

基线来意28/30、模拟动作25/30、误触发模拟黑名单0；5份失败集中于同事/朋友来电。改进后将来电目的 intent 与 claimed_relationship（自称商家/同事/朋友/家人/未说明）分开，identity_verified 始终为 false。置信度只评价来意类别，不把缺少具体会议室、文档名或无法证明自称身份当成来意不明；只在来意不明时追问。高置信普通来电模拟限时放行，高置信明确风险模拟黑名单，自称商家即使被错误标成普通来电也只能复核。7份定向复测三项均7/7；没有全面复跑新版30份，不能宣称新版30/30或真实电话准确率。

评估器不会自动重试付费请求，输出文件已存在时拒绝覆盖。命令示例：

    .\.venv\Scripts\python.exe -m server.demo.evaluation.run --output docs\demo-evidence\another-run.jsonl --concurrency 3

再次运行会调用付费模型；正常体验网页时不需要重新执行批量评估。
