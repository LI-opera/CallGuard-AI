# WP-08 通话后 AI 解释与模型工厂

2026-09-18。用户已授权 WP-08；本次实现 DeepSeek 实验模式及可切换模型工厂。未进入 WP-09，未提交 Git。应用版本 0.8.0-wp08（8），数据库仍为 Room schema 5，Android 工具链与依赖没有升级。

## 实现范围与明确限制

实时来电仍使用本地规则，生产筛查不调用 LLM。用户在记录详情确认“通话已结束”后，异步请求 AI 解释；这是用户声明，不是系统检测到通话结束。当前没有可靠的通话结束采集器，因此未实现计划中的自动结束触发或后台任务队列。AI 注释独立保存，不改写历史动作、用户标签、CallerProfile 或下一通风险分数。

手机云端 AI 默认关闭。测试临时启用后已恢复关闭；设备测试验证了该状态。页面人工操作验收尚待验证：最后 UI 导出为锁屏，不能据此声明设置界面验收通过。关闭开关阻止后续请求并尝试取消当前连接，迟到结果有代次检查；不能保证已送达第三方的请求停止处理或不计费。

不接听、不录音、不做 ASR/TTS、不进行诈骗识别。只发送有限数值特征和策略版本给模型，不发送原始号码、号码 token、联系人、通话内容或音频。后端接收关联 UUID，模型提示中不含 UUID。真实来电上传尚未实测；本轮外部调用使用合成特征。

## 模型切换与凭据

已按用户授权从 E:/Work/Auto_diagv2/.env 复制 DeepSeek 配置到本项目 .env，源文件未修改。真实凭据不写入文档；.env 被 Git 忽略。模型密钥只在服务端，手机仅保存本项目后端访问密钥，使用 AndroidKeyStore AES/GCM 加密。

DeepSeek 配置：

```dotenv
CALLGUARD_AI_ENABLED=true
CALLGUARD_AI_PROVIDER=deepseek
CALLGUARD_AI_TIMEOUT_SECONDS=20
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-chat
DEEPSEEK_API_KEY=<在本机填写，不提交>
```

切换兼容 Chat Completions 协议的供应商：设置 CALLGUARD_AI_PROVIDER=openai_compatible，并填写 MODEL_BASE_URL、MODEL_MODEL、MODEL_API_KEY，然后重启后端。BASE_URL 是不带 /chat/completions 的 HTTPS 前缀。使用该供应商自己的密钥，不复用 DeepSeek 密钥。兼容供应商只做模拟协议测试，尚未真实联调；具体供应商是否支持 JSON 模式仍需验证。

工厂拒绝未知 provider；DeepSeek 限定 api.deepseek.com，禁止重定向；超时有界且不自动重试付费请求。使用 JSON object 模式并在本地做严格结构校验。参考：[DeepSeek JSON 模式](https://api-docs.deepseek.com/guides/json_mode/)、[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/)、[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)。

## 接口、审计和失败行为

POST /v1/explanations 使用后端 Bearer 鉴权。请求严格包含 request_id、call_id、call_ended=true、features、policy_version；未知字段和私密号码字段拒绝，最大 8KiB。返回元数据、状态以及 explanation。模型对象只允许 intent、summaryCode、confidence、riskAdjustment，修正范围 -10..10；禁止最终 ALLOW/BLOCK/SILENCE 决策字段。confidence <0.7 或 intent=unknown 时展示“无法确定”。修正目前仅为注释，不参与规则计算。

Redis 负责限流及互斥，PostgreSQL ai_explanations 是审计事实。调用模型前保存 PENDING；最终保存 READY/FAILED、请求摘要、特征摘要及有限特征、实际模型、提示词和策略版本。重复请求重放原结果，不重复付费；失败的原始模型文本不保存。

进程若在 PENDING 后崩溃，可能留下待处理记录；目前不自动重试付费调用，没有管理端补偿/重试功能。同 ID 查询不会自动解决该悬挂状态。网络断开、模型失败或关闭 AI 均不影响本地筛查。现有 /v1/risk/analyze 仍为中性提供者，未接入本模型；反馈仍为本地保存。

## 文件职责

| 文件 | 本次作用 |
|---|---|
| server/app/models/factory.py、__init__.py | 环境配置、模型工厂、受限 HTTP 调用 |
| server/app/ai/schema.py、service.py、storage.py、__init__.py | 严格模型契约、提示词、幂等调用、PostgreSQL/Redis 审计 |
| server/app/api/explanations.py、server/app/main.py | 解释 API、鉴权、启动关闭与 OpenAPI |
| server/tests/test_explanations.py、test_ai_storage_integration.py | 契约/失败/工厂切换/真实存储测试 |
| android/.../ai/AiSettings.kt | 默认关闭、端点配置、加密后端凭据 |
| android/.../ai/AiExplanationClient.kt、AiExplanationStore.kt | 有界请求、结果验证、独立 AtomicFile 注释 |
| android/.../ui/records/AiSettingsPanel.kt | 云端开关及服务端配置面板 |
| android/.../ui/records/CallRecordViewModel.kt、RecordsPanel.kt、CallDetailScreen.kt | 手动结束确认、异步解释、取消及展示 |
| android/.../test/.../ai/AiExplanationClientTest.kt | 结果范围、身份、默认关闭和注释存储测试 |
| android/.../androidTest/.../screening/AiExplanationDeviceTest.kt | 合成特征的真实 DeepSeek 调用与关闭恢复 |
| android/app/build.gradle.kts | 应用版本 8 / 0.8.0-wp08 |
| scripts/start-risk-server.ps1、test-risk-server.ps1 | 加载 CALLGUARD/DEEPSEEK/MODEL 环境键 |
| scripts/export_screening_evidence.py、test_export_screening_evidence.py | 支持 WP-08 版本证据导出 |
| .env、.env.example | 本机模型配置与无密钥模板 |
| README.md、server/README.md、docs 下本报告及相关说明 | 当前能力、运行与验收边界 |

表中 android/... 对应 app/src/main/java/com/callguard/app 或 test/androidTest 对应源码目录。requirements.txt、requirement.txt、requirements.lock.txt 沿用 WP-07；httpx 已有，无需新增依赖，继续使用项目 .venv。

## 命令与验证

```powershell
.\scripts\test-risk-server.ps1 -Integration
.\scripts\build.ps1 -JdkPath E:/Android/Jdk21 -SdkPath E:/Android/Sdk -Tasks @(':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebugAndroidTest')
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p 'test_export_screening_evidence.py'
& E:/Android/Sdk/platform-tools/adb.exe -s HM9L89ROUKUCJF9P install -r android/app/build/outputs/apk/debug/app-debug.apk
& E:/Android/Sdk/platform-tools/adb.exe -s HM9L89ROUKUCJF9P install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& E:/Android/Sdk/platform-tools/adb.exe -s HM9L89ROUKUCJF9P reverse tcp:8000 tcp:8000
```

设备 instrumentation 使用 com.callguard.app.test/androidx.test.runner.AndroidJUnitRunner，分别执行 AiExplanationDeviceTest，以及 ProductionPipelineTest/FeedbackDeviceTest。前者从本机配置读取后端密钥作为 riskApiKey 参数（不在报告中展示命令行凭据），仅使用合成特征。API 由项目 .venv 的 python -m server.run --port 8000 启动，监听回环地址。

结果：Android 162 项单元测试零失败/错误/跳过；assembleDebug、assembleDebugAndroidTest、lintDebug 成功，lint 0 error/17 warning。后端 46 项通过（含真实 Redis/PostgreSQL）；导出 14 项通过；真机本地回归 2 项通过；真实 DeepSeek 探针 1 项通过。初次构建出现 Kotlin setter 名称冲突，修正为 toggleAi 后第二次构建成功，原失败日志保留。

APK SHA-256：78dda15e049c219af2ae8babb144f7a2b656343a500f7d4c52baf40654e6a023。

真实探针设备 OPPO PKB110 / Android16 API36：耗时 1458ms，是客户端请求往返时间，不是筛查耗时或铃声延迟。配置模型 deepseek-chat，接口实际返回 deepseek-flash；intent=unknown、confidence=0.2、riskAdjustment=0，展示“无法确定”。输入 LOCAL_SCORE=20 为合成数据。PostgreSQL 同 request_id 唯一审计行与手机响应相同。不据此认定意图识别准确率、真实电话上传或全阶段验收通过。

证据：[summary.json](device-evidence/wp08-20260918/summary.json)、[手机结果](device-evidence/wp08-20260918/device-ai-result.json)、[数据库核对](device-evidence/wp08-20260918/device-ai-audit.json)。同目录保留构建、后端测试、设备测试、单元 XML 和 lint XML。

## 下一步人工检查（不需额外拨打电话）

解锁手机并打开 CallGuard，检查云端 AI 默认关闭；后端地址和访问密钥已由本轮测试配置。若选择体验，开启开关，在已结束的一条记录详情点击“通话结束后生成 AI 解释”，确认结束后查看结果，再关闭开关。保留实际截图/结果后才填写 UI 通过。当前不要求重复多通 SIM 测试，也不自动进入 WP-09。


## 收尾补充（人工操作后）

人工详情页已展示“无法确定”，两条历史记录请求均READY且手机与数据库审计一致，见[收尾核对](device-evidence/wp08-20260918/closeout/acceptance.md)。前文“人工页面待验证”为此前状态，本次已补齐结果展示与请求关联证据；不代表模型语义准确性通过。模型在73分输入下返回LOW_LOCAL_RISK的矛盾已记录，现有低置信度展示守卫使其显示为“无法确定”。首页旧文案已修正，最终APK与构建结果以closeout/build-summary.json为准。自动结束触发、语音接听均未实现。
