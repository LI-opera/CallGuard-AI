# WP-03 本地规则引擎实施报告

日期：2026-09-18。状态：实现及自动化验证完成，等待用户确认后进入 WP-04。真实来电服务仍固定 ALLOW，本轮未安装 APK、未拨号、未提交 Git。

## 实施内容

纯 Kotlin 决策链：PolicyInput → 优先级短路 → RiskFeatureExtractor → RiskScoreCalculator → ProtectionLevel → PolicyResult。没有 Android API、Room 查询、网络请求、后台任务或系统拦截调用。

优先顺序：紧急联系人 → 联系人 → 永久允许 → 永久拦截 → 用户明确诈骗 → 有效临时允许 → 画像/行为特征评分 → 模式映射。前者命中即返回。因此白黑名单冲突放行，黑名单重复来电仍拦截，临时允许不能覆盖明确拦截。家人标签是减分项，不等于白名单。直接命中名单时 riskScore=null，不能冒充完成评分。

| 特征 | 权重及口径 |
|---|---|
| 基础 | +10 |
| 陌生号码 | +10；无通讯录读取实现，由调用方提供已确认状态 |
| 过去 24 小时每次额外来电 | 每个历史事件 +2，上限 +20；当前来电不在历史列表中 |
| 三分钟内重复来电 | -8，只应用一次 |
| 用户主动回拨 | -25，只应用一次 |
| 曾接听至少两次 | -15 |
| 曾拒接至少一次 | +5，不随拒接次数无限增加 |
| 用户标记营销 / 家人 | +45 / -60，互斥枚举 |

结果截断到 0–100。当前权重全部正项叠加最高实际为 90；100 分映射仅作为边界契约测试，不伪造当前特征能达到 100 的样本。结果含分数、原因、证据类别、fallbackUsed 和 policyVersion=wp03-local-v1；不包含原始号码。

| 模式 | 0–39 | 40–69 | 70–100 | 数据不可用 |
|---|---|---|---|---|
| 宽松 | ALLOW | ALLOW | SILENCE | ALLOW |
| 标准 | ALLOW | SILENCE | 至少两类证据 BLOCK，否则 SILENCE | ALLOW |
| 严格 | ALLOW | SILENCE | 至少两类证据 BLOCK，否则 SILENCE | SILENCE |

未知情况也先执行已确认的高优先级名单。snapshot=null 表示本地历史/画像不可用；RiskSnapshot() 表示已成功读取且确实无历史，二者不可混用。直接命中可信名单不是降级，因此 fallbackUsed=false；缺数据走默认才为 true。

## 计划冲突与解释

1. 计划第四十六节的定性模式说明与 WP-03 的数字阈值不一致。本轮按后面、具体可执行的 WP-03 数字表实现，并固定 policyVersion。WP-03 标准与严格正常评分阈值完全相同，不擅自创造区别；无法判断时严格静音，标准/宽松放行。后续若调整须改版本和回归用例。
2. “两条本地证据”按独立风险类别计数：近期频次、用户拒接、用户营销标记。基础分、陌生身份及负分特征不算风险证据；同类重复事件只算一类。证据数量由评分器生成，不能从外部传入虚构数量。名单明确拦截不受评分门槛影响。
3. 24 小时历史使用调用方 UTC 毫秒，窗口 (now-24h, now]，过滤未来事件并按 eventId 去重。仅适用于同一有效归一化号码、且不含本次来电；UNKNOWN token 不复用历史。
4. 三分钟特征使用调用方提供的同一启动周期单调时钟年龄，边界 0≤age≤180000ms。null 表示未知，不拿 UTC 倒推重复窗口。当前仅实现特征计算；WP-04/05 再提供真实时钟/启动周期适配与名单 UI，尚无时钟自动校正能力。
5. 计划要求“联系人但云端高风险、云端超时、数据库读取失败”冲突测试。本轮参数化用例仅验证本地边界：引擎没有云端输入，联系人先放行，云端不可用不改变完整本地结果；数据库不可用通过 snapshot=null 表达。没有真实网络超时、云端结果解析、DAO 异常捕获/故障注入；这些不标成已通过，分别留 WP-07/04。测试名称亦注明 unconsumed/local-only。

## 文件清单及作用

下表 policy/risk 生产路径前缀：android/app/src/main/java/com/callguard/app/。

| 文件 | 作用 |
|---|---|
| policy/Decision.kt | 决策枚举、带解释的 PolicyResult |
| policy/ProtectionLevel.kt | 三种模式的阈值、证据门槛和未知默认值 |
| policy/PolicyInput.kt | 可信事实输入、临时允许有效期 |
| policy/PolicyEngine.kt | 名单优先级、评分调用、结构化输出 |
| risk/RiskFeatureExtractor.kt | 历史筛选/去重、重复窗口和画像阈值；输入类型 |
| risk/RiskScoreCalculator.kt | 确定性权重、贡献项、独立证据类别 |
| android/app/src/test/java/com/callguard/app/policy/PolicyConflictTest.kt | 16 个优先级/异常状态参数用例 |
| android/app/src/test/java/com/callguard/app/policy/ProtectionLevelTest.kt | 24 个模式阈值与证据门槛用例 |
| android/app/src/test/java/com/callguard/app/risk/RiskScoreCalculatorTest.kt | 12 个权重、组合、上限用例 |
| android/app/src/test/java/com/callguard/app/risk/RiskFeatureExtractorTest.kt | 5 个窗口、去重、非法输入及实际引擎链用例 |
| android/app/build.gradle.kts | 应用版本改为 0.3.0-wp03(3)，依赖未变 |
| scripts/export_screening_evidence.py | 允许新版固定 ALLOW 诊断证据，继续兼容旧版 |
| scripts/test_export_screening_evidence.py | 新版导出格式回归 |
| README.md、docs/architecture.md | 当前能力和独立决策调用链 |
| docs/environment.md、docs/validation.md | 新报告入口，保留历史记录 |
| docs/wp03-implementation.md | 本报告 |
| docs/device-evidence/wp03-20260918/ | 本地构建产物/报告封存；目录名不意味着已有真机结果 |

没有修改 Manifest、角色代码、ScreeningService 或 Room schema；没有新增依赖、Python 包或环境配置。requirement.txt、requirements.txt、.venv 沿用现有隔离方式。Git 项目仍未建立初始提交，不能把空 git diff 当作没有改动；本轮不自动提交。

## 实际验证与证据

- Gradle assembleDebug、testDebugUnitTest、lintDebug 全部通过，构建耗时 38 秒。
- 单元测试 **118/118，失败 0、跳过 0**；其中原有 61 项，新规则测试 57 项。
- Python 导出测试 **7/7**。
- lint **0 错误、12 警告**（版本更新及 KAPT→KSP 建议），未按提示自动升级。
- 一组测试中端到端调用真实 extractor/calculator/engine：3 个历史来电＋营销标签得到 71 分、两类证据、BLOCK；补充 100ms 重复年龄降至 63 分、SILENCE；明确黑名单始终 BLOCK。
- APK：com.callguard.app，0.3.0-wp03(3)，SHA-256 b4d2ce54f4df6e82a50a0185db6089d95cb8e633af1edade2e75875542c9d939。
- 本轮未安装或执行真机来电；手机上的既有版本没有被本轮替换。WP-02 持久化真机结果仍只证明当时版本。

[机器汇总](device-evidence/wp03-20260918/summary.json)、[构建日志](device-evidence/wp03-20260918/build-test-lint.log)、[导出测试](device-evidence/wp03-20260918/export-tests.txt)。原始 JUnit XML 位于同目录 unit-test-xml/；HTML 位于 android/app/build/reports/tests/testDebugUnitTest/index.html；lint 位于 android/app/build/reports/lint-results-debug.html。封存 APK 位于 docs/device-evidence/wp03-20260918/callguard-0.3.0-wp03.apk。

实际构建/测试命令（PowerShell，项目根）：

```powershell
.\scripts\build.ps1 -JdkPath E:\Android\Jdk21 -SdkPath E:\Android\Sdk
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p 'test_export_screening_evidence.py' -v
git -c safe.directory=F:/CallBlocking status --short
rg -n 'PolicyEngine|CallerRepository' android/app/src/main/java/com/callguard/app/screening
```

最后的搜索无匹配（rg 返回 1），用来核对筛查服务未提前串入新数据/策略链；不是构建失败。

## 待办与下一步

本轮验证了给定输入下的本地规则，不代表诈骗识别准确率或真实静音/拦截。联系人/历史/规则实际读取、超时预算、异常转换、响应后落库和系统 BLOCK/SILENCE 映射属于 WP-04 及后续；尚未实现。UI 仍显示所有收到来电均放行，符合当前真实行为。

用户确认后进入 WP-04，届时用一组必要的真机用例验证实际 ALLOW/SILENCE/BLOCK 及回调响应证据，避免大规模重复拨号；具体场景与未验证事项分别登记。WP-01 默认智能优化兼容性及第二厂商覆盖仍待验证，不以本轮规则单测消除风险。
