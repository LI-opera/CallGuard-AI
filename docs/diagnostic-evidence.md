# WP-01 诊断版私有证据说明

状态：诊断版已构建、测试并安装；真实来电后的文件持久化及拔线回收仍待验证。固定ALLOW，不实现WP-02业务、名单或通话记录页面。

## 构建身份

applicationId=com.callguard.app，versionName=0.1.0-wp01-diag1，versionCode=1。仅Debug启用PERSIST_SCREENING_EVIDENCE；Release配置false（本轮未构建或测试Release）。依赖和SDK版本不变，未增加权限。

APK：docs/device-evidence/diagnostic-build-20260917/callguard-0.1.0-wp01-diag1.apk。
SHA-256：3CF86D8CC9C91EA2CEF06F08C8667BBA9403F1A5B6751C6ABC4BC5020ED5E1CE。设备base.apk哈希已核对一致。原0.1.0 APK保存在同目录baseline-0.1.0.apk；旧版证据不能当作诊断版通过证据。

## 行为与限制

onScreenCall保持入站固定ALLOW，出站不提交入站响应。respondToCall返回后采集响应完成单调时间并输出原日志，然后才在主线程执行小文件写入，不在本次响应前等待磁盘或网络。私有位置为noBackupFilesDir/screening-evidence-v1，每个UUID一个JSON，AtomicFile提交，不自动备份、不导出号码或联系人。文件包含原始日志消息体（不含logcat外层墙钟头），事件UUID、版本、PID、API、接收墙钟、接收/提交单调时钟以及固定响应状态。

JSON的respondToCall_returned仅证明调用返回，不保证系统执行，更不代替真实响铃。耗时是入口单调时间至调用返回，不含落盘，也不是响铃延迟。写入在响应后同步进行，仍可能占用主线程并影响紧接着的其他回调；仅用于有限诊断，不作正式生产持久化架构。

进程在入口/响应/提交文件之间被终止、平台响应抛异常、存储故障或容量满，都可能没有完整文件。缺文件不能证明无回调。失败只输出固定evidence_write_failed和事件ID，不输出任意异常文本，不重试来电响应。最多100个目录条目，达到上限后拒绝新增并保留旧证据；未实现自动清理。AtomicFile降低半写风险，不承诺断电时绝对无数据丢失。应用卸载/清除数据会删除证据。

## 测试与文件职责

- android/app/build.gradle.kts：Debug版本后缀与留证开关，默认关闭。
- screening/CallGuardScreeningService.kt：响应后构造诊断记录，写入失败不改变ALLOW。
- screening/DiagnosticEvidenceStore.kt：固定字段结构、UUID校验、原子文件提交、容量与重复保护。
- CallGuardScreeningServiceTest.kt：保留原入站/出站测试，新增响应先于留证、磁盘失败不重试、响应失败不伪造完成记录。
- DiagnosticEvidenceStoreTest.kt：文件回读、日志与schema、重复保护、容量保护、路径及目录失败。
- scripts/export_screening_evidence.py：run-as只读取回文件，校验schema/日志/计时，保存原字节及SHA-256清单，拒绝覆盖输出目录。
- scripts/test_export_screening_evidence.py：有效导出数据、错误计时、隐私字段、额外日志内容、文件名错配校验。

Android单测12/12通过（含原4项），Robolectric API35；导出校验5/5通过。assembleDebug成功，lint0错误6条既有依赖版本提示。未升级依赖。证据在device-evidence/diagnostic-build-20260917/的build-test-lint.log、build-summary.json、unit-test-xml/、lint-results-debug.xml、export-tests.txt、installed-diagnostic.json。构建不等于真机通过。

## 执行命令

```powershell
.\scripts\build.ps1 -JdkPath 'E:\Android\Jdk21' -SdkPath 'E:\Android\Sdk'
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p test_export_screening_evidence.py -v
& 'E:\Android\Sdk\platform-tools\adb.exe' -s HM9L89ROUKUCJF9P install -r 'F:\CallBlocking\docs\device-evidence\diagnostic-build-20260917\callguard-0.1.0-wp01-diag1.apk'
& 'E:\Android\Sdk\platform-tools\adb.exe' -s HM9L89ROUKUCJF9P shell am start -n com.callguard.app/.MainActivity
# 来电结束、重新USB连接后执行，输出目录每次须用新名称：
.\.venv\Scripts\python.exe scripts/export_screening_evidence.py --adb E:\Android\Sdk\platform-tools\adb.exe --serial HM9L89ROUKUCJF9P --output docs/device-evidence/diagnostic-build-20260917/export-01
```

安装后角色仍有效，PID14738，run-as可访问私有目录；尚未有诊断来电，证据目录不存在是当前实际状态，不能填通过。厂商耗电设置未自动修改，需用户确认更新APK后仍为完全允许。

先保持USB做一通对照，取回JSON并与实时日志配对；成功后再验证非充电熄屏。不得伪造来电或用模拟写入替代真实回调。新批次每次仍记录设备、版本、场景、UUID、收到/提交/耗时和人工响铃观察。

未提交Git，未进入WP-02。requirement.txt/.venv保持原样；本次Python脚本仅用标准库，不新增依赖。

## 真机验证更新（2026-09-17）

DIAG-U01 19:41 USB连接来电：JSON与实时日志逐项匹配，1ms。DIAG-N01 19:58非充电、用户确认熄屏至少10分钟：重连后私有文件成功取回，回调/ALLOW/1ms与系统及人工响铃观察一致，本次单通通过。记录见device-evidence/diagnostic-build-20260917/DIAG-U01-observation.md及DIAG-N01-observation.md。此前“真实落盘/拔线待验证”是准备时状态，由本节更新；不泛化为长期稳定性或整阶段通过。
