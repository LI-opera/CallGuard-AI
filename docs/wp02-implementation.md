# WP-02 数据库和号码规范化实施报告

状态：代码、自动化测试及当前 OPPO 上一组数据持久化验证完成；等待用户确认下一工作包。未进入 WP-03。实施/证据整理日期：2026-09-18。

## 阶段推进依据

用户明确“至少有一组数据作为支撑就可以进入 wp-02”，随后授权“开始wp-02”。据此调整原先两厂商各 10 通的阶段门槛，不把未执行测试改为通过。WP-01 已有代表性样本 DIAG-N01：2026-09-17 19:58，OPPO PKB110/Android 16，0.1.0-wp01-diag1(1)，事件 e3808e0f-b618-41db-ab25-470d59ee0990；收到回调 497938635ms、respondToCall 返回 497938636ms，差值 1ms。用户确认真实响铃、被测手机挂断、期间未解锁。原始证据见 [事件 JSON](device-evidence/diagnostic-build-20260917/export-02/e3808e0f-b618-41db-ab25-470d59ee0990.json) 和 [观察记录](device-evidence/diagnostic-build-20260917/DIAG-N01-observation.md)。

该样本使用“完全允许后台行为”；默认智能优化下的既有缺回调问题、第二厂商及未完成场景保留待验证。不能由一次成功推导总体成功率。未执行的十通批次已标注取消。

## 实施范围与设计决策

- Room 三张表和 DAO、跨表事务、临时规则到期清理、号码 E.164 规范化及迁移测试。
- 号码 HMAC token 使用 Android Keystore 非导出密钥；同一安装相同号码格式变体得到同一 token。空/隐藏/短号/非电话 URI/无法解析号码得到一次性 UNKNOWN token，避免将所有未知来电合并成一个画像。隐藏状态优先于号码文本。
- E.164 只在内存中返回，不写数据库或日志。数据库放 noBackupFilesDir，防止数据库被单独备份恢复而失去对应设备密钥。未实现跨设备迁移；卸载/清除数据导致标识重建。Keystore 失败向调用者报错，不退回明文或硬编码密钥。
- 计划“所有 DAO 提供 recordIncomingCallAndUpdateProfile”按一个跨表事务入口实现：CallRecordDao 唯一负责，CallerRepository 委托。CallerProfileDao 只读、RuleEntryDao 自己负责过期清理事务，避免三处复制跨表写逻辑。
- 插入重复事件 ID 抛错并回滚画像新建或计数更新；不是重复累计。相同号码不同事件累计计数且保留一个画像。
- TEMP_ALLOW 查询只取 enabled、createdAt<=now、expiresAt>now，先删除 expiresAt<=now 的临时规则。只做存储筛选，不执行优先级和拦截。
- DAO/Repository 是同步工作线程 API；生产库未启用主线程访问。调用方负责关闭。WP-04 接入时需明确调用线程及响应时序。
- riskScore=null、riskLevel=NOT_EVALUATED、policyVersion=wp02-no-policy；接听/拒接计数尚无采集来源，不冒充已计算。

WP-01 没有已部署 Room 数据库。schema v1 是专门构造的历史结构测试夹具；当前正式导出 schema v2，1→2 增加 normalizationStatus 默认 UNKNOWN。通过的是人工构造空/有数据 v1 的迁移测试，不声称曾有真实用户 v1 数据升级。

## 新增和修改文件

路径均相对项目根；Kotlin 根为 android/app/src/main/java/com/callguard/app/。

| 文件 | 作用 |
|---|---|
| android/build.gradle.kts | 固定 KAPT 2.0.21 插件 |
| android/app/build.gradle.kts | Room/libphonenumber/测试依赖、schema 导出、0.2.0-wp02(2) |
| caller/PhoneNumberNormalizer.kt | 严格输入检查、E.164、HMAC token、UNKNOWN 类型 |
| caller/AndroidNumberTokenizer.kt | Keystore 密钥创建与加载 |
| data/AppDatabase.kt | 三表注册、私有文件位置、1→2 migration |
| data/CallerRepository.kt | 规范化结果转通话记录，委托事务 |
| data/entity/CallerProfileEntity.kt | 号码画像与计数 |
| data/entity/CallRecordEntity.kt | 事件记录及未计算风险状态 |
| data/entity/RuleEntryEntity.kt | 规则数据及有效期 |
| data/dao/CallerProfileDao.kt | 画像查询 |
| data/dao/CallRecordDao.kt | 事件查询与跨表原子写入 |
| data/dao/RuleEntryDao.kt | 规则校验、存储、查询与过期清理 |
| android/app/schemas/com.callguard.app.data.AppDatabase/2.json | Room 编译器生成的正式 schema |
| android/app/src/test/java/com/callguard/app/caller/PhoneNumberNormalizerTest.kt | 42 种号码输入参数测试 |
| android/app/src/test/java/com/callguard/app/caller/NumberTokenTest.kt | 格式稳定性、不同密钥隔离、UNKNOWN 独立性 |
| android/app/src/test/java/com/callguard/app/data/AppDatabaseTest.kt | 5 项数据库/迁移测试 |
| android/app/src/test/resources/schema-v1.sql | 迁移用人工历史 schema |
| android/app/src/androidTest/java/com/callguard/app/data/ProcessPersistenceTest.kt | 分两次运行的真实进程持久化验证 |
| scripts/export_screening_evidence.py | 同时允许旧诊断版与当前版本的事件格式 |
| scripts/test_export_screening_evidence.py | 增加新版导出兼容回归 |
| README.md、docs/architecture.md | 当前能力、版本及数据流说明 |
| docs/wp01-remaining-acceptance.md | 保留历史核对并注明新推进条件 |
| docs/device-evidence/diagnostic-baseline-20260917-200938/baseline-plan.md | 十通方案未执行/取消标记 |
| docs/wp02-implementation.md、docs/validation.md、docs/environment.md | 实施结果及当前环境入口 |
| docs/device-evidence/wp02-20260918/ | 构建日志、测试 XML、设备结果、APK/身份记录 |

requirement.txt/requirements.txt 与 .venv 保留；未新增 Python 包。Manifest、角色代码、onScreenCall 固定 ALLOW 逻辑未修改。新增必要依赖：Room 2.7.2、libphonenumber 8.13.55、Android Test runner 1.6.2、ext JUnit 1.2.1；原 AGP/Kotlin/Gradle/Compose 版本不升级。KAPT 选择与现有 Kotlin 2.0.21 配合，编译有语言回退 1.9 提示，已经实际构建验证。

## 验证结果

| 检查 | 实际结果 |
|---|---|
| assembleDebug / assembleDebugAndroidTest | 成功 |
| JVM Android 单元测试 | 61/61，无跳过：42 号码格式、2 token、5 数据库、12 既有回归 |
| Room 数据库测试 | 新库/关闭重开、重复号码计数、重复 ID 两种回滚、临时规则过期边界、空 v1/有数据 v1 迁移通过 |
| lintDebug | 0 错误、12 警告：11 版本更新提示、1 KAPT→KSP 建议；本轮不自动升级 |
| Python 导出工具 | 6/6，通过新旧版本字段校验 |
| OPPO 一组进程重启持久化 | seed 与 readAfterRestart 各 1/1；PID 4719→5186；2 条记录、1 个画像、callCount=2，token 未变 |
| 安装校验 | 本机 APK SHA-256 与设备 base.apk 一致；角色查询仍为 com.callguard.app；测试后已打开 MainActivity |
| WP-02 新 APK 真实来电 | 本轮未拨入，待验证；不能把旧版来电样本当新版实测 |

首轮遇到测试使用 RoomDatabase.use 的编译错误，改为 finally close 的测试扩展后通过；号码前缀显式拒绝多个/中间加号。失败日志和成功日志都保留。

真机数据写入独立 wp02-persistence-check.db，仅含合成测试输入；不清除 callguard.db 或旧诊断证据。测试中的 force-stop 是数据库持久化前置，不算“强停后自动来电恢复”测试，也不等于设备重启/断电持久化验证。测试 APK 留在设备以便复核。

[机器汇总](device-evidence/wp02-20260918/summary.json)、[真机原始读回结果](device-evidence/wp02-20260918/device-read-after-restart.txt)、[进程及计数](device-evidence/wp02-20260918/process-check.json)、[APK 身份](device-evidence/wp02-20260918/apk-identity.json)。单元 HTML：android/app/build/reports/tests/testDebugUnitTest/index.html；原始 XML 已封存于上述证据目录。

APK：com.callguard.app / 0.2.0-wp02 / versionCode 2。SHA-256：350BBFFF4781C1E7B75E91F82B9A50E3E0123D63E4CE30538EF66604935F70DB。

## 执行与复现命令

项目根 PowerShell（复现 seed 会重置专用测试数据库，不影响业务数据库）：

```powershell
.\scripts\build.ps1 -JdkPath E:\Android\Jdk21 -SdkPath E:\Android\Sdk -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest')
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p 'test_export_screening_evidence.py' -v
$adb = 'E:\Android\Sdk\platform-tools\adb.exe'
$device = 'HM9L89ROUKUCJF9P'
& $adb devices -l
& $adb -s $device install -r android/app/build/outputs/apk/debug/app-debug.apk
& $adb -s $device install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $device shell am instrument -w -r -e class 'com.callguard.app.data.ProcessPersistenceTest#seed' com.callguard.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $device shell am force-stop com.callguard.app
& $adb -s $device shell am instrument -w -r -e class 'com.callguard.app.data.ProcessPersistenceTest#readAfterRestart' com.callguard.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $device exec-out run-as com.callguard.app cat no_backup/wp02-process-check.json
& $adb -s $device shell cmd role get-role-holders --user 0 android.app.role.CALL_SCREENING
& $adb -s $device shell am start -n com.callguard.app/.MainActivity
Get-FileHash android/app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
$packagePath = (& $adb -s $device shell pm path com.callguard.app).Trim().Replace('package:', '')
& $adb -s $device shell sha256sum $packagePath
git -c safe.directory=F:/CallBlocking status --short
```

## 剩余边界

数据层已经过基本功能验证；没有规则引擎、BLOCK/SILENCE 执行、真实来电数据库写入、记录页、AI 或云端。本轮无需扩大电话采样。若补新版电话回归，仅一通未保存正常显示号码的 SIM 来电，关联新版回调/响应事件和人工响铃；未执行前保持待验证。后续按用户确认进入 WP-03 本地规则引擎。没有自动提交 Git。
