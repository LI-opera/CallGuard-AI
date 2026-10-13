> WP-05 最新入口：[窗口与规则操作报告](wp05-implementation.md)。以下保留各工作包过程记录。

> 当前以文末 WP-04 链路和 [实施报告](wp04-implementation.md) 为准；前面的 WP-01/02/03 内容为分阶段记录。

# 当前架构与文件职责

本目录 F:\CallBlocking 即项目根，不再额外套一层 callguard。

当前只有一个 Android app 模块，按职责分包，避免在探针阶段引入无用模块。

```text
Compose 首屏 → CallScreeningRoleGateway → Android RoleManager
                          ↑ 每次回到前台重新查询实际角色

Android Telecom → CallGuardScreeningService.onScreenCall
                → 仅入站：固定 ALLOW → respondToCall
                → 脱敏 logcat（事件 ID / API / 时间 / 耗时）
```

角色申请使用 Activity Result API。角色授权与实际来电覆盖率分开验证；角色被替换后，回到前台可检测。未实现后台角色失效通知。

| 文件 | 职责 |
|---|---|
| android/settings.gradle.kts | 仓库与 app 模块声明 |
| android/build.gradle.kts | AGP、Kotlin、Compose 编译插件版本 |
| android/gradle.properties | JVM、AndroidX 配置 |
| android/gradlew、gradlew.bat、gradle/wrapper/* | 固定 Gradle 发行版 |
| android/app/build.gradle.kts | API 29–36、Compose、JUnit/Robolectric 测试依赖 |
| android/app/src/main/AndroidManifest.xml | 入口 Activity、受 BIND_SCREENING_SERVICE 保护的服务 |
| android/app/src/main/res/values/styles.xml | Android 窗口主题 |
| android/app/src/main/res/drawable/ic_callguard.xml | 本地矢量应用图标 |
| android/app/src/main/java/com/callguard/app/MainActivity.kt | 三种角色状态、申请按钮、未实现能力说明 |
| android/app/src/main/java/com/callguard/app/platform/CallScreeningRoleGateway.kt | 角色查询、系统申请、前台状态刷新 |
| android/app/src/main/java/com/callguard/app/screening/CallGuardScreeningService.kt | 系统回调、固定 ALLOW、脱敏计时日志 |
| android/app/src/test/** | 生产 Service 回调的 JVM Android 模拟测试 |
| scripts/build.ps1 | 项目内 Gradle 缓存、短临时目录、构建/测试/lint；退出恢复环境 |
| requirement.txt、requirements.txt | Python 依赖入口；当前无第三方 Python 依赖 |
| .venv/ | 本地 Python 3.12.10 隔离环境，忽略提交 |
| .gitignore | 排除缓存、构建产物、本机路径及密钥 |
| docs/ | 审查、决策、环境、架构、验收证据 |

server/ 和 test-fixtures/ 仅预留目录，无运行服务或样例业务实现。WP-02 已加入 Room 与 caller 包；WP-03 已加入独立 policy、risk 包。

`.venv` 仅隔离 Python 包，不管理 Kotlin/JDK/SDK，也不应保存长期配置或密钥。Android 本机 SDK 路径在被忽略的 local.properties；Gradle 依赖通过固定版本和 Wrapper 管理。项目没有云端配置或 API Key。

## WP-02 数据层

独立调用链：PhoneNumberNormalizer → CallerRepository → CallRecordDao 的跨表事务 → Room。该调用链未接入 onScreenCall，也没有新增后台任务。同步 DAO/Repository 必须由工作线程调用；数据库调用失败向调用方传播，不吞掉半写错误。

- AppDatabase：noBackupFilesDir 下的私有数据库、明确的 1→2 迁移，无破坏式回退。调用方负责关闭数据库。
- CallerProfile/CallRecord/RuleEntry Entity：号码画像、通话记录、规则存储；没有原始号码字段。
- CallRecordDao：一次事务写记录与更新画像计数；重复事件 ID 失败并回滚。
- CallerProfileDao：画像读取；RuleEntryDao：规则存取与到期清理，不计算规则优先级。
- PhoneNumberNormalizer：有效号码转 E.164；无效/隐藏输入返回独立 UNKNOWN token，不合并隐藏来电画像。
- AndroidNumberTokenizer：Android Keystore HMAC 密钥；同一安装内有效号码生成稳定标识。E.164 只在内存返回，不写入数据库或日志。

风险分数尚未计算，因此存储 null/NOT_EVALUATED；answeredCount/rejectedCount 保留默认值，不能作为已监测接听结果。详见 [WP-02 实施报告](wp02-implementation.md)。

## WP-03 纯本地决策链

调用方提供 PolicyInput → 优先级名单判断 → RiskFeatureExtractor → RiskScoreCalculator → ProtectionLevel → PolicyResult。无 IO、协程、系统电话操作或数据库读写；依赖只指向普通 Kotlin 类型。

PolicyInput 中的布尔值必须是已读取的可信本地事实；尚未读取/读取失败的历史和画像使用 snapshot=null，不能伪装成空历史。未来 WP-04 负责查询及异常转换；已确认的白黑名单可以在其他数据缺失时继续生效。snapshot 中 prior history 仅含同一归一化号码的历史入站事件，不含当前来电；UNKNOWN token 不关联历史。

RuleEntryDao 负责查有效规则，后续适配层将结果映射为 PolicyInput。RiskSnapshot 的回拨与接听/拒接事实目前尚无系统采集；不能拿画像默认 0 证明没有发生。评分引擎只验证给定输入的确定性结果。

[WP-03 报告](wp03-implementation.md)说明时间窗口、证据门槛和计划冲突裁决。真实链路仍是 onScreenCall → 固定 ALLOW；WP-04 未开始。

## WP-04 真实链路

Telecom → onScreenCall（入站门禁、UUID、单调截止时间）→ ScreeningCoordinator → AndroidScreeningDataSource（工作线程：号码规范化、联系人、Room 名单/画像/历史）→ PolicyEngine → CallResponseMapper → respondToCall → 独立写入队列（诊断 JSON、Room 通话记录/画像事务）。

ScreeningRuntime 持有进程级有界执行器：2 个读取线程、8 项队列；独立 2 个截止线程；1 个写入线程、32 项队列。超时后晚到结果不能再次响应。数据库/联系人即使卡住也不占用截止线程；队列满时降级并记录固定错误码。进程被系统终止可能丢失未完成的异步落盘，不声明强一致持久化。

Room schema v3 新增 nullable latencyMs，旧记录迁移后保持 null。响应耗时是服务入口至 respondToCall 返回的 elapsedRealtime 差值，不是铃声开始延迟。已提交并不等于系统最终 UI/响铃已验证。

真实链路暂不填造用户接听、拒接、主动回拨或同启动周期重复时间，相关负分只在将来有真实采集器后启用。当前支持持久化的用户标签 MARKETING/FAMILY/SCAM，但正式管理界面未实现。调试测试 APK 只针对明确 eventId 对应号码设置可恢复验收数据，不在正式服务增加强制决策开关。

## WP-05 持久化时间窗口

CallRecord 新增 receivedElapsedMs、bootCount、retryStartedAtMs、retryWindowUntil，响应后随记录和画像计数事务一起写入。读取同 token、同 boot 的最新有效窗口：首次建立180秒窗口，第二/三次计数递增但不滑动延长。窗口不可读/没有启动身份/跨启动时不应用重复减分。

RuleEntry 新增 createdElapsedMs、expiresElapsedMs、bootCount。生产查询 activeForClock 使用这三个字段判断 TEMP_ALLOW；createdAt/expiresAt 保留 UTC 展示用途。v3及更早的临时规则没有启动身份，生产查询时清理，不回退到不可靠的墙上时钟。旧 activeForNumber 保留用于历史 API 测试，生产筛查不调用它。

最近记录面板 → IO线程 RuleActionsRepository → 根据已有 eventId 找到 NORMALIZED token → upsert用户规则。临时允许与永久白名单使用不同稳定ID，重复操作不会新增多条同类型规则。再次点击临时允许表示重新从操作时刻计5分钟；不会改写历史决策。

WP-05时UI仅显示最近5条；WP-06已用分页记录/详情/反馈替换该入口（见下）。没有存储原始号码，因此此阶段用来电时间定位操作目标，不伪造号码掩码。


## WP-06 本地反馈与记录视图

MainActivity → CallRecordViewModel → IO线程打开Room → 游标分页/自然日聚合/详情。反馈按钮 → FeedbackHandler.enqueue独立事务提交FeedbackEvent → 后续IO事务按sequence应用标签或名单并标记applied。打开/前台恢复/刷新时重试pending；筛查回调不等待队列，不做网络同步。

Room v5只新增feedback_events及唯一feedbackId；历史记录不可变，当前标签与当时原因分开显示。相同标签不增加版本或计数；NORMAL撤销标签影响但不等于白名单，HARASSMENT是用户骚扰反馈45分，与原MARKETING权重相同，证据码分开。名单确认会禁用相反类型规则。

没有原始号码/掩码，没有意图分类结果，没有后台实时反馈恢复或云同步；详见[实施与验证](wp06-implementation.md)。


## WP-07 有界Risk API（默认不接入真实来电）

可注入路径：ScreeningCoordinator先得到本地PolicyResult → 可选HttpCloudRiskGateway → RiskApiClient（共享剩余deadline，至多一次重试） → POST /v1/risk/analyze。生产ScreeningRuntime的网关仍为null。独立定时器在云端超时时保留已完成的本地结果，迟到响应不能重复提交。

FastAPI → 严格Pydantic → Redis限流/幂等 → PostgreSQL审计事实 → Redis缓存。NeutralProvider只给出0修正/unknown，非AI结论。不存在云端最终BLOCK字段；不让云端创造第二份本地风险证据。反馈仍在本地，未同步。

真实本机Redis/PostgreSQL与手机合成请求已联调，详情见[WP07](wp07-implementation.md)。


## WP-08 更新

用户确认通话结束 → CallRecordViewModel IO协程 → AiExplanationClient → POST /v1/explanations → ExplanationService → Redis互斥/限流与PostgreSQL预存PENDING → create_model → DeepSeek → 严格结构校验 → 审计与独立AtomicFile注释。模型结果不写入Room规则/画像，不接入ScreeningRuntime。默认关闭；没有自动结束检测和后台任务队列。

完整文件职责、命令、限制和原始证据见 [WP08实施报告](wp08-implementation.md)。
