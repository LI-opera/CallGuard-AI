# WP-00/01 验收准备核对

> 历史快照：本文4项测试、0.1.0 APK及真机待验证描述对应初次审查，不能代表当前状态。当前诊断版12项Android测试、真机证据及剩余缺口见 [剩余验收核对](wp01-remaining-acceptance.md) 和 [诊断说明](diagnostic-evidence.md)。历史内容保留供追溯。
核对日期：2026-09-17。依据本轮实际读取的 Kotlin、Gradle、源/合并 Manifest、JUnit XML、APK 哈希及 Gradle/JDK 输出。
本轮只修改验收文档；未重跑构建或测试、未改功能/依赖、未提交 Git、未进入 WP-02。现有 Git 文件仍未提交。

## 1. 四项单元测试及实际证据

| 测试名称 | 测试对象 | 关键断言 |
|---|---|---|
| incomingCallWithoutNumberIsAllowedExactlyOnce | CallGuardScreeningService.onScreenCall；Mockito 入站 Details | 捕获响应数为 1；提交的是同一 Details；disallowCall/rejectCall/silenceCall/skipCallLog/skipNotification 全 false |
| outgoingCallDoesNotSubmitIncomingScreeningResponse | 同一生产回调，出站 Details | 响应集合为空 |
| unavailableRoleIsUnsupported | CallScreeningRoleGateway.state | 角色不可用时 UNSUPPORTED |
| foregroundRefreshDetectsGrantAndRoleLoss | observeRoleLoss/state 与 LifecycleRegistry | 初始 NOT_HELD；模拟持有角色并 ON_RESUME 后 HELD；模拟移除角色、再 ON_RESUME 后 NOT_HELD |

XML：android/app/build/test-results/testDebugUnitTest/TEST-com.callguard.app.screening.CallGuardScreeningServiceTest.xml（2 tests，0 failures，0 errors；2026-09-17T05:23:51.106Z）；
android/app/build/test-results/testDebugUnitTest/TEST-com.callguard.app.platform.CallScreeningRoleGatewayTest.xml（2 tests，0 failures，0 errors；2026-09-17T05:23:48.461Z）。
HTML：android/app/build/reports/tests/testDebugUnitTest/index.html。

均使用 Robolectric API 35。入站测试的 handle 未配置，依赖 Mockito 默认 null；没有专门断言 handle==null。每次回调提交一次已覆盖，但跨重复回调去重未测试也未实现。
CapturingService 覆盖 submitResponse，未调用真正 respondToCall；未测试真实系统绑定、服务生命周期、授权 UI、来电铃声、日志脱敏或实际时延。不得用这 4 项替代真机验收。

## 2. Manifest 核对

源：android/app/src/main/AndroidManifest.xml。
合并结果：android/app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml。

服务实际完整类名 com.callguard.app.screening.CallGuardScreeningService：
- android:permission="android.permission.BIND_SCREENING_SERVICE"
- android:exported="true"
- intent-filter action="android.telecom.CallScreeningService"

源与合并结果一致。BIND_SCREENING_SERVICE 是绑定服务的权限保护，不是应用主动申请的 uses-permission。
合并清单唯一 uses-permission 为 com.callguard.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION；同名 permission 的 protectionLevel=signature。
没有 INTERNET、READ_CONTACTS、READ_CALL_LOG、READ_PHONE_STATE、RECORD_AUDIO、短信权限。
额外合并组件：androidx.startup.InitializationProvider exported=false；ProfileInstallReceiver exported=true，受 android.permission.DUMP 保护（也不是应用申请 DUMP 权限）。
debuggable=true，是 debug 验收包，不是发布包。

## 3. onScreenCall 核对

源码：android/app/src/main/java/com/callguard/app/screening/CallGuardScreeningService.kt。
- direction != DIRECTION_INCOMING 时记录 ignored 后返回：出站及非入站方向均不提交入站响应。
- 入站固定五个布尔标志 false；同步 submitResponse → respondToCall，无规则判断、BLOCK/SILENCE 分支。
- Service 自包含，无 Activity 引用、Activity 启动状态检查或由 Activity 初始化的业务对象；源码层面不依赖页面打开。实际系统唤起能力仍待真机验证。
- 响应前只有单调时钟读取、UUID 生成、方向判断、日志和响应对象构造；无网络、数据库、线程等待、协程或外部模型。同步日志/UUID 也有开销，不能因此声称零耗时或保证达标。
- 日志只含事件 ID、API、方向、时间、ALLOW/忽略原因；不读取 handle，不打印 Details 或原始号码。
- responded 日志在 respondToCall 返回后输出，表示应用提交调用完成，不是 Telecom 确认或实际响铃证明。
- 响应路径没有异常捕获/重试；若异常提前中断，可能没有 responded。本轮只记录这一边界，不新增修复功能。

## 4. 角色状态核对

MainActivity 用 ActivityResultContracts.StartActivityForResult；无论系统返回成功还是取消，都重查 gateway.state()，不依赖 resultCode 直接设成 HELD。
未持有角色时取消应保持 NOT_HELD，这是代码推导，未有取消 UI 自动化或真机证据。
observeRoleLoss 先查询一次，并在 Activity ON_RESUME 时重查。角色被替换的模拟状态测试通过；真实设置页替换待验证。
只支持申请返回及前台生命周期刷新，没有后台实时监听/通知，也没有持续轮询；应用一直保持 resumed 时的外部角色变化不保证立即反映。
“未授权，或来电筛查角色已失效”是统一提示，不区分首次未授权与历史授权丢失。

## 5. 版本核对

| 配置 | 实际值 | 依据 |
|---|---|---|
| minSdk | 29 | app/build.gradle.kts、merged manifest |
| targetSdk | 36 | 同上 |
| compileSdk | 36 | app/build.gradle.kts |
| AGP | 8.13.0 | android/build.gradle.kts |
| 应用 Kotlin / Compose compiler plugin | 2.0.21 / 2.0.21 | android/build.gradle.kts |
| Gradle Wrapper | 8.14.3 | wrapper properties + 本轮 gradlew --version |
| 构建 JDK | Temurin 21.0.12.1+1-LTS | 本轮 java -version、Gradle JVM 输出；已有构建 daemon 日志指向 E:\Android\Jdk21 |
| Java/Kotlin 字节码目标 | 17 / 17 | compileOptions、jvmTarget |
| 应用 | 0.1.0，versionCode 1，com.callguard.app | app 配置、merged manifest |

API 36 为固定基线，不声明当日最新稳定 SDK。Gradle 输出的内嵌 Kotlin 版本不作为应用 Kotlin 的唯一依据。
APK SHA-256 复核仍为 3E101D239231DB1ACE86AC0874ED4B7465490594DF00CBCA2210D6CA2AD92A72，与现有验证报告一致。

## 6. 发现的问题与处理状态

| 问题 | 本轮处理 |
|---|---|
| 原设备表只有汇总空行，未保留逐通证据 | 改为设备资料、20 次基准来电、独立终止场景、原始日志索引与汇总方法 |
| 旧步骤只写 am kill，未完整区分三类终止 | 普通后台终止、最近任务划掉、设置强行停止分别列项，独立记录 PID/角色/结果 |
| 取消申请无专门测试；模拟角色丢失不是实际替换 | 明确测试缺口，真机均待验证 |
| Service 测试绕过系统响应边界 | 标为生产回调单测，不标系统端到端通过 |
| 耗时与成功率容易混用 | 单调时钟差值；失败不填 0、不删样本；成功率单独统计；未响应不能靠 P95 掩盖 |
| BLOCK/SILENCE 容易误纳入本轮 | 明确未实现/未验证、后续待办 |
| 正常进程恢复与 force-stop 混淆 | 强行停止单独做限制观察，重新手动启动后的结果另记，不能冒充自动恢复 |

本轮验收仅“角色授权 + 固定 ALLOW 探针”。两家厂商各 10 次合格普通陌生来电，回调率 100%、P95 < 2000 ms，同时保留每次响铃观察；三类终止分别出结论。无真机数据则全部待验证。P95 不包含系统绑定、进程冷启动到回调入口的时间，不能表示端到端响铃延迟。
