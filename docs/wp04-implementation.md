> 最新结论：一组 OPPO 真实 SIM 验证已完成，ALLOW 96ms / SILENCE 70ms / BLOCK 78ms，均已关联 Room 与人工观察；测试标签和专用黑名单已恢复。未观察到拨打方自动结束，实际为手动挂断。下文保留实施过程状态；阶段确认及 WP-05 尚未进行。

# WP-04 生产筛查链路实施与验收

日期：2026-09-18。状态：代码/构建/自动化验证完成，0.4.0-wp04(4) 已安装 OPPO；真实 SIM 放行、静音、拦截验收待完成。未进入 WP-05、未提交 Git。

## 当前行为

真实 onScreenCall 只接受入站处理，创建 UUID、receivedAtMs、receivedWallTimeMs 和 800ms 截止时间；出站只记忽略日志、不提交筛查响应。将原始 handle 临时交给工作线程规范化，后续记录只存 token，不存原始号码。

链路：号码规范化/Keystore → 联系人查询 → Room 有效名单 → 画像/近期历史 → PolicyEngine → 系统 CallResponse → 响应后异步留证和通话/画像事务。Activity 是否打开不影响这条链路。读取、评分和写入没有网络依赖；Manifest 没有 INTERNET 权限。

- ALLOW：disallow=false、reject=false、silence=false。
- SILENCE：disallow=false、reject=false、silence=true；不是完全拦截。
- BLOCK：disallow=true、reject=true、silence=false。
- 三种情况均 skipCallLog=false、skipNotification=false，不隐藏系统记录。

映射依据 [Android CallResponse.Builder](https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder)。[CallScreeningService 官方说明](https://developer.android.com/reference/android/telecom/CallScreeningService)要求及时响应，并说明无 READ_CONTACTS 授权时只发送非联系人来电；有权限时本应用主动查询 Contacts Provider 并优先放行联系人。权限查询/Provider 异常走保守降级，不将异常当联系人不存在。隐藏/非电话等未收到系统回调的情况不能由应用保证处理。

新增 READ_CONTACTS 清单权限及首页可选授权按钮，回前台刷新状态；没有自动授予权限，也没有新增 READ_CALL_LOG/READ_PHONE_STATE。仍固定标准模式，名单管理和模式设置界面尚未实现。

## 截止、异常和记录

- 读取线程池 2 线程/8 队列；独立截止线程池 2 线程；写入 1 线程/32 队列，全部进程级复用。不会每通无限创建线程或队列。
- 800ms 从回调入口单调时钟计算，包括线程排队及初始化。截止线程不等候数据库锁。完成结果与截止竞争使用原子状态，只提交一次；迟到查询不能改写已提交决策。
- 截止/读异常/队列满时，使用最近已确认事实：已查明联系人/白名单仍 ALLOW、黑名单仍 BLOCK，未知默认标准 ALLOW。若名单查询本身被锁而尚未读出黑名单，则只能未知放行，不宣称能凭空知道黑名单。
- respondToCall 抛异常时不重试、不写成功证据。API 正常返回只能证明提交方法返回，不证明系统最终响铃/阻断成功。
- 响应返回后，写入线程先保存 debug JSON 再保存 Room 记录/画像事务。任一步失败均记录固定事件码，不日志输出异常消息/原始号码；记录保存失败不再次提交响应。
- 进程死亡、写入队列满或磁盘故障仍可能丢失响应后的记录，此处不是强一致日志系统。线程截止不能保证系统调度暂停/进程被杀时的硬实时响应。
- schema v3 新增 nullable latencyMs，v2→v3 只加列，旧记录保持 null。latencyMs=respondToCall 返回的 elapsedRealtime−onScreenCall 入口 elapsedRealtime；不是系统响铃延迟。JSON 与真实 Room 使用同一返回时刻。
- 诊断 JSON schema v2 增加实际 decision、riskScore、matchedRules、policyVersion、fallbackUsed；旧 schema v1 继续保留并可导出。目录仍为 screening-evidence-v1，文件 schemaVersion 区分格式；100 条上限不静默删原始证据。

## 真实数据与尚未采集的事实

数据库读取使用同一 token 的最近 24h、最多 10 条历史（频次加分已在 10 条封顶），读取实际 profile 标签/计数。SCAM 标签为明确诈骗，优先于临时允许；MARKETING/FAMILY 为评分项。规则和画像读取错误不伪装为空记录。

尚无接听/用户拒接/回拨采集，不把系统 BLOCK 自动累计为“用户拒接”。尚无可靠跨服务/启动周期的重复来电单调时间存储，因此真实链路 previousIncomingAgeMs=null，不应用未验证的重复降分。紧急联系人独立来源、用户反馈 UI、临时允许 UI 留后续工作包；普通联系人已有读取适配。

## 文件清单

生产 Kotlin 路径前缀 android/app/src/main/java/com/callguard/app/。

| 文件 | 作用 |
|---|---|
| screening/ScreeningDeadline.kt | 单调时钟与截止预算 |
| screening/ScreeningCoordinator.kt | 调度、快照降级、一次提交与响应后任务 |
| screening/AndroidScreeningDataSource.kt | 真实联系人/Room 查询与通话事务持久化 |
| screening/ScreeningRuntime.kt | 进程级有界执行器与 applicationContext 数据源 |
| screening/CallResponseMapper.kt | ALLOW/SILENCE/BLOCK 系统映射 |
| screening/CallGuardScreeningService.kt | 从系统回调接入协调器和精确提交计时 |
| screening/DiagnosticEvidenceStore.kt | 新决策证据格式，兼容旧记录 |
| data/dao/CallRecordDao.kt | 有界历史查询 |
| data/entity/CallRecordEntity.kt | latencyMs 字段 |
| data/AppDatabase.kt | schema v3 和 2→3 迁移 |
| android/app/schemas/com.callguard.app.data.AppDatabase/3.json | 编译器导出的新 schema；v2 保留 |
| MainActivity.kt、android/app/src/main/AndroidManifest.xml | 当前能力说明、联系人权限和申请入口 |
| android/app/build.gradle.kts | 0.4.0-wp04(4)；依赖不变 |
| android/app/src/test/.../ScreeningCoordinatorTest.kt | 截止、晚到结果、异常、拥塞和写入顺序 |
| android/app/src/test/.../CallResponseMapperTest.kt | 三种系统标志与日志/通知保留 |
| android/app/src/test/.../CallGuardScreeningServiceTest.kt | 更新服务入口测试；出站、成功留证、提交失败等 |
| android/app/src/test/.../AppDatabaseTest.kt | 新增 v2→v3、历史耗时为空的迁移验证 |
| android/app/src/androidTest/.../ProductionPipelineTest.kt | 真机真实 Room/Keystore、锁竞争，合成输入 |
| android/app/src/androidTest/.../LiveValidationFixture.kt | 指定真实事件 token 的可恢复验收数据设置；仅测试 APK |
| android/app/src/androidTest/.../LiveRecordInspectionTest.kt | 指定真实事件只读导出 Room 记录 |
| scripts/export_screening_evidence.py、test_export_screening_evidence.py | 新旧格式、三种决策、隐私字段白名单回归 |
| README.md、docs/architecture.md、environment.md、validation.md | 当前能力及报告入口 |
| docs/wp04-implementation.md、device-evidence/wp04-20260918/ | 本报告、构建/自动化/真实验收原始证据 |

Python .venv、requirement.txt/requirements.txt 保持，无新增依赖。未修改 WP-03 阈值以迁就测试。

## 已执行结果

- assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug：成功。
- Android 单元测试 **126/126**，无跳过；导出工具 **10/10**。
- lint **0 错误、13 警告**：12 条既有版本/KAPT 提示，1 条 StaticFieldLeak 指向进程级数据源。已核对该字段只持有 applicationContext，不持有 Activity/Service；生命周期与进程一致，保留告警说明，不以禁用规则掩盖。
- OPPO PKB110 Android16/API36，一组 ProductionPipelineTest **1/1**：真实 Room/Keystore 但合成号码，捕获提交边界。ALLOW 21ms、SILENCE 14ms、BLOCK 7ms；真实数据库事务持锁时降级 ALLOW 800ms。数据位于独立 wp04-pipeline-check.db，未清除业务 callguard.db。
- 正式 APK SHA-256 **3211C6E1E2474C257340C20CD8A746B8A39C83F7E8F32581B059E61D115EAADF**，设备 base.apk 哈希一致；角色查询仍为 com.callguard.app。
- 上述合成数据不是实际 SIM 筛查证据，没有据此标记真实静音/拦截通过。

[测试汇总](device-evidence/wp04-20260918/summary.json)、[最终完整构建日志](device-evidence/wp04-20260918/build-3.log)、[真机仪器测试输出](device-evidence/wp04-20260918/device-pipeline.txt)、[各场景原始耗时](device-evidence/wp04-20260918/pipeline-check.json)。单元 HTML：android/app/build/reports/tests/testDebugUnitTest/index.html；原始 XML 已封存于 unit-test-xml/。测试工具只读检查类的最后一次构建见 test-inspection-build.log，不改变生产 APK。

## 实际命令

```powershell
.\scripts\build.ps1 -JdkPath E:\Android\Jdk21 -SdkPath E:\Android\Sdk -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest')
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p 'test_export_screening_evidence.py' -v
$adb = 'E:\Android\Sdk\platform-tools\adb.exe'
$device = 'HM9L89ROUKUCJF9P'
& $adb -s $device install -r android/app/build/outputs/apk/debug/app-debug.apk
& $adb -s $device install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $device shell am instrument -w -r -e class 'com.callguard.app.screening.ProductionPipelineTest' com.callguard.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $device exec-out run-as com.callguard.app cat no_backup/wp04-pipeline-check.json
& $adb -s $device shell cmd role get-role-holders --user 0 android.app.role.CALL_SCREENING
& $adb -s $device shell am start -n com.callguard.app/.MainActivity
Get-FileHash android/app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
```

## 真实电话验收（等待用户）

本轮只安排一组必要场景，每种动作一通，不重复多组。第一通普通陌生号码 ALLOW；收到实际回调、提交和 Room 记录并关联人工观察后，再给同一个 token 写可恢复的 MARKETING 测试标签验证 SILENCE，再添加专用 BLOCK 规则验证 BLOCK；完成立即 RESTORE，仅恢复本轮改动，保留真实通话证据。测试工具不接管服务决策，实际服务仍读真实 Room。

更新：00:40 ALLOW 实测通过（96ms、未降级、Room 关联一致），见 device-evidence/wp04-20260918/allow-observation.md；已应用同一 token 的 MARKETING 测试标签并备份，等待下一通 SILENCE，结束后需 RESTORE。正式名单管理界面未实现，测试夹具不能宣称为用户功能。用户未反馈前保持待验证。完整逐次表见 [本轮真机验收表](device-evidence/wp04-20260918/live-validation.md)。

阶段仍未验收：需实际 SIM 回调→规则→系统响应→数据库→人工响铃观察关联。无互联网、联系人授权/拒绝变化、真实冷启动和不同厂商完整行为没有全部真机证据；不得由单元/合成输入测试补成通过。后续仅针对失败原因最小修复，不自动进入 WP-05。

## 00:46 真实静音验证更新

同一 token 第二次来电：SILENCE，67分，70ms，未降级，Room 计数2。用户确认来电界面显示、无铃声、无振动、拨打方挂断，本机本次通过。见 device-evidence/wp04-20260918/silence-observation.md。已配置专用 BLOCK 规则，等待最后一通；测试标签与规则尚待恢复，阶段未整体验收。

## 00:58 拦截验证与收尾

实际 BLOCK_RULE→BLOCK，78ms，未降级，Room 同事件核对一致，画像计数3；用户报告无界面/铃声/振动，拨打方听到忙线提示并手动挂断。本机本次基本功能通过，自动结束不填通过。见 device-evidence/wp04-20260918/block-observation.md。三动作各一通已具备真实证据，RESTORE 已成功，无需扩大重复样本。联系人变化、不同厂商、无互联网真实来电等尚未验证项继续保留；不把本组推广为全面稳定性验收。
