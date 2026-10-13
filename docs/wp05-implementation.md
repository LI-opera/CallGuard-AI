# WP-05 临时允许与重复来电

2026-09-18。已实现并安装 0.5.0-wp05(5)；构建、单元测试、一组真机数据库重放及一通 TEMP_ALLOW 真实来电核对完成。界面与临时允许按钮写入已验证。没有进入 WP-06或提交 Git。

## 实现与时间语义

1. 首次可归一化的陌生来电读取不到当前启动周期的有效窗口时，建立 retryStartedAtMs=回调入口 elapsedRealtime、retryWindowUntil=起点+180000ms、repeatCount=0，响应后随 CallRecord 和 Profile 事务落库。Profile.firstSeenAt 沿用真实首次来电时间。
2. 同 token、同 boot 的后续来电在固定起点至截止（含180秒边界）内，repeatCount 递增，窗口不滑动；特征提取使用相对该起点的单调年龄。评分只减一次8分，不随重复次数叠加。黑名单优先，营销等中风险仍可 SILENCE。
3. 同一进程重建数据源或关闭重开数据库可恢复窗口。设备重启、启动身份不可用、时钟年龄倒退或窗口到期时不沿用旧重复特征。不是每个服务实例重新启动一个空窗口。
4. “允许本次（未来5分钟）”实际写 TEMP_ALLOW，有效区间为操作时刻至+300000ms，截止点排除。不会接回或修改已经结束的通话，也不会绕过明确黑名单。重复点击表示重新计5分钟，稳定ID覆盖同类型规则，不插入重复条目。
5. “加入白名单（永久）”才写 ALLOW，跨重启保留，按既有优先级高于 BLOCK。临时与永久使用不同ID，允许操作不修改历史决策或画像计数。
6. production activeForClock 根据 bootCount、createdElapsedMs、expiresElapsedMs 判断并删除过期/无效 TEMP_ALLOW。UTC createdAt/expiresAt 用于展示，不用于延長临时授权。永久规则不受用户回拨系统时间而意外失效。

单调时间选择依据 [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock)，设备启动次数依据 [Settings.Global.BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global)。没有改设备系统时间。读取不到启动次数时临时允许操作失败，不回退到墙上时钟；已有临时规则按保守策略清理。

## 最小界面

首页增加可滚动的最近5条记录、刷新、已提交动作、重复提示，以及两个不同的允许按钮。未知号码不显示号码规则按钮。只显示来电时间用于区分，不保存/显示原始号码，也不伪造号码掩码。

重复事实根据 repeatCount 显示“短时间重复来电”；只有 matchedRules 中实际含 REPEAT_CALL 才显示已减8分，黑名单直接拦截不冒充做过减分。操作和读取均在 IO 线程，成功提示只在数据库返回成功后显示。

本轮只是 WP-05 必要操作入口，不实现 WP-06 的分页、详情页、营销/诈骗反馈、统计面板。UI 的实际点击链路尚待人工验证，底层 RuleActionsRepository 已实际测试。

## 数据和迁移

Room v3→v4：

- CallRecord：新增 receivedElapsedMs、bootCount、retryStartedAtMs、retryWindowUntil，均 nullable、默认NULL。
- RuleEntry：新增 createdElapsedMs、expiresElapsedMs、bootCount，均 nullable、默认NULL。
- 保留旧记录及计数，旧数据不填造启动身份/窗口。旧 TEMP_ALLOW 缺少单调时钟依据，生产查询清理；永久 ALLOW/BLOCK 保留。
- 新 schema 由编译器导出到 android/app/schemas/com.callguard.app.data.AppDatabase/4.json；2/3历史schema保留。

历史 activeForNumber 墙上时钟接口保留供旧版数据测试；真实筛查只调用 activeForClock。已有800ms截止、一次响应及响应后写入机制不变。若上一通异步落盘尚未完成或失败，下一通可能读不到其重复窗口，保守地不降分；不会等待写入而延误当前响应。并发来电不是本轮强一致重复计数保证，记录中的窗口仅代表当次实际读取的快照。

24小时频次聚合仍按UTC历史查询；本轮保证180秒/5分钟窗口不受墙上时钟跳变延长，不声称历史频次在任意时间修改下不变。

## 修改文件与职责

生产 Kotlin 前缀 android/app/src/main/java/com/callguard/app/。

| 文件 | 作用 |
|---|---|
| platform/WindowClock.kt | 可注入三元时钟：UTC、elapsedRealtime、BOOT_COUNT |
| screening/RetryWindow.kt | 固定重复窗口、跨启动校验与计数 |
| screening/ScreeningCoordinator.kt | 筛查快照携带窗口和启动身份，调度不变 |
| screening/AndroidScreeningDataSource.kt | 读取持久窗口、临时规则、真实重复特征与响应后保存 |
| data/RuleActionsRepository.kt | 根据已有事件token写5分钟/永久规则，不处理原始号码 |
| data/entity/CallRecordEntity.kt、RuleEntryEntity.kt | 新增时间依据字段 |
| data/dao/CallRecordDao.kt | 最新同启动窗口和最近5条查询 |
| data/dao/RuleEntryDao.kt | 单调时钟筛选/清理及完整时间字段校验 |
| data/AppDatabase.kt | schema4、3→4迁移 |
| ui/RecentCallsPanel.kt、MainActivity.kt | 最近记录、两个允许操作、返回前台刷新 |
| android/app/build.gradle.kts | 0.5.0-wp05(5)，无依赖升级 |
| android/app/src/test/.../data/WindowIntegrationTest.kt | 7项真实数据源+Room+规则链集成测试 |
| android/app/src/test/.../data/AppDatabaseTest.kt | 新增v3迁移不补造窗口、旧临时规则清理检查 |
| android/app/src/androidTest/.../screening/WindowReplayDeviceTest.kt | 独立测试数据库的真实设备重放，不改系统时间 |
| scripts/export_screening_evidence.py、test_export_screening_evidence.py | WP-05版本兼容 |
| README.md、docs/architecture.md、environment.md、validation.md | 最新边界与报告入口 |
| docs/wp05-implementation.md、device-evidence/wp05-20260918/ | 报告、原始日志、APK和待验证UI证据 |

未新增依赖、网络权限或 Python 包，requirement.txt/requirements.txt/.venv 保持。评分权重基线仍 wp03-local-v1，实际集成版本由0.5.0-wp05区分，未修改阈值来迁就测试。

## 实际验证结果

- Android测试 **134/134**，0失败/跳过；新增7项窗口集成和1项迁移检查。
- 导出工具 **11/11**。
- assembleDebug、assembleDebugAndroidTest、lintDebug成功。UI提示微调后的最终构建见build-final.log；完整单元测试见build-3.log，无业务逻辑在其后变更。
- lint 0错误、13警告，含已有依赖/KAPT建议及已核对仅保存applicationContext的进程级数据源提示。
- OPPO PKB110 Android16/API36：WindowReplayDeviceTest和必要的ProductionPipelineTest回归 **2/2**。窗口重放使用真实Room/Keystore，注入时钟和合成输入；没有发生SIM电话。

| 重放场景 | 实际结果 |
|---|---|
| 首次 | 20分 ALLOW，repeatCount=0 |
| 第二次 | 14分 ALLOW，repeatCount=1 |
| 第三次（重建数据源） | 16分 ALLOW，repeatCount=2，窗口未延长 |
| TEMP_ALLOW截止前1ms且UTC回拨 | 命中TEMP_ALLOW |
| TEMP_ALLOW恰好到期 | 恢复评分，63分 SILENCE |
| 黑名单重复 | BLOCK_RULE → BLOCK |
| 永久白名单模拟跨启动 | ALLOW_RULE → ALLOW |

单元测试另外覆盖180秒边界、同窗口中风险59分仍SILENCE、未知号码不能建规则、缺启动身份、真实关闭重开Room、旧数据库迁移。临时规则的写入和到期均调用生产接口，不用伪代码或固定返回值。

首轮测试编译中 RuntimeEnvironment 泛型调用不匹配已修复。随后Robolectric legacy SQLite多连接有2项锁冲突，测试改为注入可控的同一Room连接，并真实关闭/重开验证；没有跳过断言。真机测试仍使用独立连接通过。失败日志build-1/build-2保留，成功日志build-3保留。

原始 [重放结果](device-evidence/wp05-20260918/window-replay.json)、[设备测试输出](device-evidence/wp05-20260918/device-tests.txt)、[汇总](device-evidence/wp05-20260918/summary.json)。单元XML封存于unit-test-xml；HTML为android/app/build/reports/tests/testDebugUnitTest/index.html。重放记录中的1ms是合成响应占位计时，不是实测性能或系统提交证据；不计入电话延迟统计。

APK com.callguard.app / 0.5.0-wp05(5)，SHA256 **51A2DF4A810C008D4C6FDFC77315475589071DDF855916AE2BF83E00348F4555**。设备安装文件哈希见installed-apk-hash.txt，角色查询见role.txt。

## 执行命令

```powershell
.\scripts\build.ps1 -JdkPath E:\Android\Jdk21 -SdkPath E:\Android\Sdk -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest')
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p 'test_export_screening_evidence.py' -v
$adb='E:\Android\Sdk\platform-tools\adb.exe'
$device='HM9L89ROUKUCJF9P'
& $adb -s $device install -r android/app/build/outputs/apk/debug/app-debug.apk
& $adb -s $device install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $device shell am instrument -w -r -e class 'com.callguard.app.screening.WindowReplayDeviceTest,com.callguard.app.screening.ProductionPipelineTest' com.callguard.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $device exec-out run-as com.callguard.app cat no_backup/wp05-window-check.json
& $adb -s $device shell am start -n com.callguard.app/.MainActivity
```

## 待验证

永久白名单按钮真机点击、临时到期后的真实 SIM 来电及180秒内真实重复来电仍待验证；相关自动边界测试和合成重放已完成，不冒充真实电话结果。不要求多批重复测试。WP-06未开始，阶段确认由用户决定。

## 临时允许按钮实际写入核对

用户截图显示操作成功提示；2026-09-18 01:52:44只读检查确认对应测试号码仅有一条user来源TEMP_ALLOW，enabled=1、同boot=38，单调有效期精确300000ms，检查当时剩约90秒。没有永久ALLOW或BLOCK规则。见device-evidence/wp05-20260918/ui-rule-inspection.json。按钮→真实数据库写入已验证；后续有效期内真实来电结果见下。

## TEMP_ALLOW 单次真实来电结果

用户报告01:54拨入，原始回调墙钟为 2026-09-18T01:53:49.186+08:00。事件 `dd7c9281-e692-4f78-9db8-db825fd13d0a` 命中 TEMP_ALLOW，67ms提交ALLOW，无降级；距临时规则到期尚余26888ms，Room记录一致。用户确认显示来电界面、“响铃或振动：是”、被呼叫方挂断；不把合并回答拆成响铃和振动各自通过。详见 [逐项记录与原始证据](device-evidence/wp05-20260918/temp-allow-observation.md)。本次仅更新验收文档，无业务代码变更，不重复构建。
