# CallGuard AI

CallGuard AI 是分阶段开发的安卓来电筛查实验项目。当前安卓应用为 **WP-08 通话后 AI 解释版（0.8.0-wp08）**：实时筛查由本地规则处理，手机云端 AI 默认关闭；用户确认通话结束后可手动生成解释。独立的本机网页用于**文字模拟**“AI 对话 → 意图判断 → 同一虚构号码重拨”，不接入真实电话。详见 [WP-08 实施报告](docs/wp08-implementation.md)和[文字模拟说明](server/README.md)。

| 模块 | 当前边界 |
|---|---|
| Android 来电筛查 | 角色授权、本地规则、ALLOW/SILENCE/BLOCK、记录和反馈；真机证据仅覆盖已记录的设备与场景 |
| 通话后 AI 解释 | 可选的后端模型调用，不影响当次实时拦截结果 |
| 文字模拟台 | 使用虚构 ID 演示问答、五分钟模拟放行、模拟拦截和复核；规则只保存在本机服务内存 |
| Phone Link 语音链路 | 人工接听和双向通话已有实验；程序化音频接入与自动接听尚未打通 |

快速体验文字模拟：在项目根目录运行 `.\scripts\start-text-call-demo.ps1`，打开 `http://127.0.0.1:8765/`。脚本读取本机 `.env` 中的模型配置，发送输入可能产生模型费用。请勿输入真实号码、验证码或私人信息。没有 `.env` 中的有效模型密钥时，可运行单元测试，但网页不能生成 AI 回复。

临时规则按单调时钟到期，设备重启后失效；永久白名单保留。窗口与通话记录在响应后一起保存，进程被杀或写入失败仍可能缺少历史。当前标准模式、800ms 本地查询截止保护保持不变；新增 AI 解释位于独立的通话后操作中。WP-04 已有 OPPO 三动作各一通实测；WP-05 另有一通真实 TEMP_ALLOW → ALLOW（67ms）与 Room 落库证据。真机原始证据[仅保存在本机](docs/device-evidence/README.md)，不上传 GitHub；其余新版真机场景不据此推断通过。

## 文档入口

- [30 份文字模拟来电、基线与优化后定向复测](docs/demo-evidence/text-call-evaluation-20260926.md)；本地网页：http://127.0.0.1:8765 （使用 scripts/start-text-call-demo.ps1 启动）。当前网页还提供“同一虚构来电者重拨”和事件时间线；五分钟放行、拦截和复核仅在本机进程内模拟，详见 [服务端说明](server/README.md)。

- [蓝牙接通与 AI 模块隔离首轮](docs/voice-bluetooth-initial.md) / [Voice 开发说明](voice/README.md)

- [V2 转接与 AI 语音验证路线（规划，未实现）](docs/voice-verification-roadmap.md)

- [WP-07 接口、运行与验证](docs/wp07-implementation.md)
- [WP-06 实施、测试与真机步骤](docs/wp06-implementation.md)
- [WP-05 实施、窗口语义与验证](docs/wp05-implementation.md)

- [WP-04 实施与待完成真机验收](docs/wp04-implementation.md)

- [WP-03 规则实现、冲突处理与测试](docs/wp03-implementation.md)

- [WP-02 实施、验证与阶段推进依据](docs/wp02-implementation.md)

- [范围审查与完成标准](docs/review.md)
- [架构与逐文件职责](docs/architecture.md)
- [MVP 边界决策](docs/decisions/0001-mvp-boundary.md)
- [WP-01 诊断版私有证据与导出](docs/diagnostic-evidence.md)
- [环境说明](docs/environment.md)
- [构建测试记录](docs/validation.md)
- [WP-01 当前剩余验收清单](docs/wp01-remaining-acceptance.md)
- [真机验证步骤](docs/device-validation.md) / [设备矩阵](docs/device-matrix.md)

## 固定版本

| 项目 | 版本 |
|---|---|
| JDK | Temurin 21.0.12.1；字节码目标 Java 17 |
| Android Studio（命令行不依赖它） | 2026.1.3 / AI-261.26222.65.2613.15948027 |
| Gradle / Android Gradle Plugin | 8.14.3 / 8.13.0 |
| Kotlin / Compose compiler plugin | 2.0.21 / 2.0.21 |
| Compose BOM / Activity Compose | 2024.12.01 / 1.10.0 |
| Lifecycle | 2.8.7 |
| Room / KAPT | 2.7.2 / 2.0.21 |
| libphonenumber | 8.13.55 |
| Android Test runner / ext JUnit | 1.6.2 / 1.2.1 |
| SDK | min 29，compile/target 36，Build Tools 35.0.0 |
| JUnit / Robolectric / Mockito | 4.13.2 / 4.14.1 / 5.14.2 |
| Python | 3.12.10 |
| FastAPI / httpx | 0.115.12 / 0.28.1（项目 .venv） |

## 构建

打开 android/ 可导入 Android Studio。需要上述 JDK、SDK，并联网访问 Google Maven、Maven Central、Gradle 官方仓库。无需全局安装 Gradle。

```powershell
cd F:\CallBlocking
.\scripts\build.ps1 -JdkPath 'E:\Android\Jdk21' -SdkPath 'E:\Android\Sdk'
```

该脚本执行 :app:assembleDebug、:app:testDebugUnitTest、:app:lintDebug。只修改当前进程环境，结束恢复；缓存存放 .gradle-user-home/，JDK socket 临时文件使用 .tmp/。新机器传入实际安装路径，并更新或移除本机 android/local.properties。

- APK：android/app/build/outputs/apk/debug/app-debug.apk
- 测试报告：android/app/build/reports/tests/testDebugUnitTest/index.html
- lint：android/app/build/reports/lint-results-debug.html

## Python 隔离

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirement.txt
```

requirement.txt 引用 requirements.txt，requirements.lock.txt 锁定后端依赖。.venv 仅隔离 Python 包，不管理 Kotlin/JDK/SDK，不存长期配置或密钥。Android 本机路径在忽略提交的 local.properties；服务端由项目 .venv 运行，凭据位于忽略提交的 .env。

## 验收边界

用户已明确允许以现有一组真实功能证据推进 WP-02，不再要求本阶段进行两家厂商各 10 通的重复采样。已有 OPPO 单通回调、ALLOW 提交和真实响铃证据；第二厂商、默认智能优化下的稳定性等仍待验证。该推进决定不等于跨机型验收通过。后续每个工作包保留基本功能测试，完成后等待确认。

WP-06 构建、143项单元测试、12项导出测试、2项独立数据库设备测试通过；真机升级4条历史记录及画像保持一致。已有一通真实骚扰反馈后SIM拨入证据：73分、100ms提交BLOCK，用户确认无界面/响铃/振动；测试标签已恢复并核对为NORMAL。尚未宣布全部阶段验收通过。

WP-07服务启动：`.\scripts\start-risk-server.ps1 -StartInfrastructure`；真实存储测试：`.\scripts\test-risk-server.ps1 -Integration`。依赖安装仅使用项目.venv，.env保存本机配置，requirements.lock.txt锁定实装依赖。
