# WP-06 记录页与本地反馈闭环

2026-09-18。实现并安装 com.callguard.app 0.6.0-wp06 (6)。本地构建、自动测试和独立数据库设备重放完成；一通真实 SIM 反馈后拨入已核对；测试标签已恢复并核对为NORMAL。未进入 WP-07，未提交 Git，未升级依赖。

## 范围与语义

- 首页按设备当前时区的自然日查询已保存记录，分别统计 ALLOW/SILENCE/BLOCK 及其中的降级次数。统计系统响应提交，不推断真实响铃、接听或通话结束。
- 记录按 receivedAt DESC、id DESC 排序，20条一页，用最后一条的时间/ID作为游标。读取21条判断有无下一页；加载更多期间新来电不会导致旧页重复，刷新重新加载首页。
- 详情分开展示当时决策/规则证据和当前用户标签/名单，反馈不改写历史 CallRecord。
- 默认简明模式隐藏分数、耗时、策略版本，使用中文原因。显示偏好本地保存。
- “这是正常电话”写 NORMAL，清除原有骚扰等标签影响，按基础规则重新评分；不等于加入白名单，也不能覆盖黑名单。
- “这是骚扰电话”写 HARASSMENT，以明确的 USER_HARASSMENT 证据加45分，与原营销标签相同权重。覆盖标签，不累计加分；不冒称诈骗或 AI 推断。可与频次等既有证据共同影响决策。
- 白名单/黑名单需在界面确认，关闭该号码相反类型的已有规则，再写稳定ID的用户规则。联系人优先级保持不变；临时允许不能覆盖黑名单。
- 保留5分钟临时允许入口；按WP-05单调时钟规则清理过期规则。

## 与计划书的明确差异

当前只保存不可逆号码 token，不能恢复号码掩码；页面明确显示“号码未保存”，按时间和事件ID定位。没有意图识别器，显示“意图未分析”，用户标签单独展示。没有服务端/网络同步，反馈 syncStatus 固定 LOCAL_ONLY，页面明确未同步；“联网后同步”待后续服务端与接口实现，不能标记通过。既有 policyVersion wp03-local-v1 保留基础规则权重身份；应用版本0.6.0-wp06标识此次反馈接入与 HARASSMENT 映射扩展。

## 数据流与失败恢复

1. UI在IO线程根据callId读取归一化记录与Profile版本；未知号码拒绝写反馈。
2. enqueue独立事务先提交FeedbackEvent（反馈ID、callId、token、action、createdAt、profileVersion、applied、syncStatus）。feedbackId唯一；同一号码最近同一记录同一操作复用已存事件。相反反馈允许新增事件。
3. 后续在IO线程按数据库自增sequence顺序应用，每个事件的Profile/Rule更新与applied标记在同一事务中完成。相同标签不增加Profile版本；不触碰接听/拒接/来电计数。名单变更不伪造Profile标签。
4. 保存后进程死亡或应用失败时，事件保留pending；下次打开、回到前台或刷新时重试。不是后台实时同步，也不保证用户未重开时立即重试。反馈按钮在本地应用成功后才提示生效。
5. 新反馈携带旧Profile版本且不属于重复点击时拒绝写入，要求刷新。按队列顺序应用时更新当前Profile的标签字段，不用旧快照覆盖并发来电计数。
6. 筛查回调不等待反馈队列、Activity或网络；仍沿用现有800ms截止和响应后异步落库。落库失败/进程被杀可能缺少历史，不保证零丢失。

Room v4→v5只新增feedback_events及feedbackId唯一索引，不重建旧业务表。旧迁移链完整保留。

## 文件职责

以下路径均相对于项目根目录：

| 文件 | 作用 |
|---|---|
| android/app/src/main/java/com/callguard/app/data/entity/FeedbackEventEntity.kt | 可追溯、本地待应用反馈事件 |
| android/app/src/main/java/com/callguard/app/data/dao/FeedbackDao.kt | 幂等ID、按序待处理查询与已应用标记 |
| android/app/src/main/java/com/callguard/app/data/AppDatabase.kt | v5注册及4→5迁移 |
| android/app/schemas/com.callguard.app.data.AppDatabase/5.json | Room生成的v5结构 |
| android/app/src/main/java/com/callguard/app/data/dao/CallRecordDao.kt | 游标分页、当天聚合 |
| android/app/src/main/java/com/callguard/app/data/dao/CallerProfileDao.kt | 不改计数的标签更新 |
| android/app/src/main/java/com/callguard/app/data/dao/RuleEntryDao.kt | 明确关闭相反名单规则 |
| android/app/src/main/java/com/callguard/app/feedback/FeedbackHandler.kt | 先保存事件、事务应用、幂等重试 |
| android/app/src/main/java/com/callguard/app/ui/home/HomeViewModel.kt | 当前时区自然日统计 |
| android/app/src/main/java/com/callguard/app/ui/records/CallRecordViewModel.kt | IO调度、分页/详情状态、反馈与前台恢复 |
| android/app/src/main/java/com/callguard/app/ui/records/RecordsPanel.kt | 统计、列表、简明模式 |
| android/app/src/main/java/com/callguard/app/ui/records/CallDetailScreen.kt | 详情、解释与反馈/名单确认 |
| android/app/src/main/java/com/callguard/app/MainActivity.kt | 接入生命周期ViewModel和新面板 |
| android/app/src/main/java/com/callguard/app/ui/RecentCallsPanel.kt | 已由新面板替换并删除 |
| android/app/src/main/java/com/callguard/app/risk/RiskFeatureExtractor.kt | 新用户骚扰标签类型 |
| android/app/src/main/java/com/callguard/app/risk/RiskScoreCalculator.kt | 骚扰反馈45分单项证据 |
| android/app/src/main/java/com/callguard/app/screening/AndroidScreeningDataSource.kt | Profile骚扰标签接入真实策略输入 |
| android/app/build.gradle.kts | 应用版本0.6.0 / WP06后缀；依赖未升级 |
| android/app/src/test/java/com/callguard/app/data/FeedbackIntegrationTest.kt | 8项反馈、恢复、分页与真实策略整合测试 |
| android/app/src/test/java/com/callguard/app/data/AppDatabaseTest.kt | 添加真实v4结构迁移验证，旧测试接入v5迁移 |
| android/app/src/test/resources/schema-v4.json | 原v4导出结构测试夹具 |
| android/app/src/androidTest/java/com/callguard/app/screening/FeedbackDeviceTest.kt | 独立真机SQLite/Keystore反馈重放，不拨SIM电话 |
| scripts/export_screening_evidence.py | 允许WP06版本与新反馈证据码 |
| scripts/test_export_screening_evidence.py | WP06证据回归 |
| README.md、docs/architecture.md、docs/environment.md、docs/device-validation.md | 当前实现、架构、环境和必要真机步骤 |
| docs/device-evidence/wp06-20260918/ | 原始日志、APK、迁移摘要、UI和测试报告 |

## 测试结果与原始报告

- 143/143 Android单元测试，0失败；12/12 Python导出测试。
- assembleDebug、assembleDebugAndroidTest、lintDebug成功。lint 0错误、14警告：既有版本/KAPT建议、既有applicationContext静态持有提示、新增SharedPreferences KTX写法建议，无新增依赖。
- OPPO PKB110 Android16/API36：FeedbackDeviceTest + ProductionPipelineTest，2/2通过。均为独立测试数据库和合成请求，不属于真实来电验收。
- 反馈测试：骚扰反馈→下一次SILENCE，正常反馈→下一次ALLOW；重复不加权；待处理事件关闭重开后恢复；故障注入事务回滚后重试；旧版本拒绝、未知号码拒绝；相反名单切换；历史记录与来电计数保持不变。
- 首轮1项迁移夹具测试因无索引表缺少可选indices字段失败，使用optJSONArray后通过；未跳过断言，build-1.log保留。
- 真实设备升级前后：4条记录、1份画像、1条规则各表内容SHA-256完全相同，Room版本4→5；feedback_events初始0条。证据before-upgrade.json/after-upgrade.json。随后打开详情时按正常规则清理过期TEMP_ALLOW，不属于迁移数据丢失。
- 已核对首页和最新01:53:49详情可见，角色仍由本应用持有，未自动点击生产反馈按钮。原始ui*.xml/png。

原始结果在 [证据目录](device-evidence/wp06-20260918/)，单元XML在unit-test-xml，设备日志device-tests.txt，迁移摘要before/after-upgrade.json。真实反馈后SIM来电已核对一通，见本报告末尾。

APK SHA-256：`f3bb79f5ec098d3cd368c235af8fb214de63c2a58328567ef46904d4639763d5`。已与设备已安装base.apk哈希匹配。

## 实际执行命令

```powershell
.\scripts\build.ps1 -JdkPath E:/Android/Jdk21 -SdkPath E:/Android/Sdk -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest')
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p test_export_screening_evidence.py -v
$adb='E:/Android/Sdk/platform-tools/adb.exe'
$device='HM9L89ROUKUCJF9P'
& $adb -s $device install -r android/app/build/outputs/apk/debug/app-debug.apk
& $adb -s $device install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $device shell am instrument -w -r -e class 'com.callguard.app.screening.FeedbackDeviceTest,com.callguard.app.screening.ProductionPipelineTest' com.callguard.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $device exec-out run-as com.callguard.app cat no_backup/wp06-feedback-check.json
& $adb -s $device shell am start -n com.callguard.app/.MainActivity
& $adb -s $device shell cmd role get-role-holders android.app.role.CALL_SCREENING
```

另执行只读run-as数据库复制（前后均无生产WAL、quick_check=ok），SQLite查询计算三表摘要；pm path / sha256sum核验安装包；uiautomator dump / screencap / pull查看应用界面。设备输入仅滚动和打开详情，没有代用户反馈。

## 一组必要真机验收步骤（本次拨入已完成，标签已恢复NORMAL）

1. 最新01:53:49记录（事件dd7c9281…）详情，确认当前名单无；点击一次“这是骚扰电话”，观察“当前用户标签：骚扰电话”及本地生效提示。不是点击黑名单。
2. 用同一未保存的普通SIM号码拨入一通，记录时间、是否显示来电界面、响铃、振动分别是否发生、挂断方。不能预填通过。
3. 导出并关联新eventId、应用版本、USER_HARASSMENT、决策、回调/响应单调时间差、Room和用户实际观察。当前4条近期记录且无优先名单时，基础20+频次8+骚扰45=73分，预计BLOCK；实际以回调证据为准。
4. 测试后把测试号码标回“这是正常电话”，核对标签恢复，不再要求额外重复拨测。跨厂商、真实进程故障恢复、联网同步仍待验证。

这组只验证用户反馈到下一通决策；不把自动重放或已有WP04/05电话当作WP06实测。不自动开始WP07。


## 02:25 实际结果

事件 `c8bebafa-b1ce-4bf6-afed-26a79a0fb3bf`，02:25:37.326收到回调，USER_HARASSMENT参与评分，总分73，100ms提交BLOCK且无降级，Room数据一致。用户确认无来电界面、无响铃、无振动，拨打方听到忙线提示后手动挂断。不是系统自动挂断证据。详见[原始记录及用户观察](device-evidence/wp06-20260918/feedback-call-observation.md)。单次真实反馈链路已验证；剩余收尾为恢复测试号码NORMAL标签，不增加拨测。


## 本组收尾已核对

用户确认当前标签恢复“正常电话”。只读数据库确认NORMAL反馈已应用，无启用名单规则，02:25历史记录仍为BLOCK/73分/100ms，未被改写。证据：[恢复快照](device-evidence/wp06-20260918/feedback-restoration.json)。本组真实反馈链路及标签恢复完成，无新增拨测；云同步与其他未验证项仍保持原边界，WP07未开始。
