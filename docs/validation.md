> WP-05 最新状态：见 [实施与验证报告](wp05-implementation.md)。依赖未升级，Room schema v4，使用已有系统启动计数与单调时钟接口。

> WP-04 最新状态：见 [实施与真机验收报告](wp04-implementation.md)。依赖未升级；新增 READ_CONTACTS 运行时授权入口，Room schema v3。

> WP-03 最新状态：见 [规则引擎实施报告](wp03-implementation.md)。本轮没有新增或升级依赖。

> WP-02 最新状态：见 [实施与验证报告](wp02-implementation.md)。下文保留 WP-00/01 历史环境/测试记录。

# WP-00/WP-01 验证记录（2026-09-17）

状态：**代码构建及基础自动化验证通过；真机验收待完成。未进入 WP-02。**

## 最终结果

| 检查 | 结果 |
|---|---|
| :app:assembleDebug | 通过，生成 debug APK |
| :app:testDebugUnitTest | 4 项通过，0 失败，0 错误 |
| :app:lintDebug | 通过，0 errors、6 warnings |
| APK 权限检查 | 仅 AndroidX 自有 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION；无录音、麦克风、短信、联系人、网络权限 |
| Python 隔离 | .venv 为 Python 3.12.10，sys.prefix != sys.base_prefix；空依赖入口安装成功 |
| 真机角色/来电/耗时 | 未验证，不能宣称达标 |

最后一次构建 BUILD SUCCESSFUL in 25s（56 个任务，29 执行、27 已最新）。

APK：android/app/build/outputs/apk/debug/app-debug.apk，22,740,358 字节。
SHA-256：3E101D239231DB1ACE86AC0874ED4B7465490594DF00CBCA2210D6CA2AD92A72。

## 测试范围

- CallGuardScreeningServiceTest：无号码入站固定 ALLOW，原始 Details 原样提交且仅一次；五个拒接/静音/隐藏标志均 false。去电不提交入站响应。
- CallScreeningRoleGatewayTest：角色不可用显示不支持；授权和角色移除后，前台生命周期刷新能检测变化。

测试使用 API 35 Robolectric。Service 测试调用生产 onScreenCall，只替换平台提交边界，没有跳过回调断言；没有模拟真实基带通话。角色页面、系统绑定、进程死亡重启和实际响铃均留给真机。API 29/36 真机兼容性未验收。

## 执行命令

环境检查用 Get-Location、rg --files、Get-ChildItem、git status --short --branch、java -version、javac -version、gradle --version、python --version、adb version、sdkmanager --version。后五个 Android/Java 工具不在 PATH，随后通过 E:\Android 的安装目录和绝对路径验证，SDK 平台/Build Tools 通过目录核对；读取 AndroidStudio/product-info.json 确认 IDE 版本。

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirement.txt
git init

# 使用已安装 Gradle 生成 Wrapper，进程环境设置 JAVA_HOME/ANDROID_HOME/
# GRADLE_USER_HOME 与 JAVA_TOOL_OPTIONS 的短临时目录，未改全局环境。
# Gradle 实际安装路径：
# E:\Android\Gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat
# 参数：-p android wrapper --gradle-version 8.14.3 --distribution-type bin --console=plain --no-daemon

.\scripts\build.ps1 -JdkPath 'E:\Android\Jdk21' -SdkPath 'E:\Android\Sdk'
# 内部：gradlew.bat -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain

& 'E:\Android\Sdk\platform-tools\adb.exe' devices -l
& 'E:\Android\Sdk\build-tools\35.0.0\aapt.exe' dump permissions android\app\build\outputs\apk\debug\app-debug.apk
& 'E:\Android\Sdk\build-tools\35.0.0\aapt.exe' dump badging android\app\build\outputs\apk\debug\app-debug.apk
Get-FileHash android\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
git -c safe.directory=F:/CallBlocking status --short
```

Git 的 safe.directory 只用于单次命令，因为沙箱创建仓库与外部构建进程身份不同；未修改全局 Git 设置。仓库尚无提交，原计划的初始提交未执行。

## 修复与残留提示

- 修复 Windows JDK Unix-domain socket 临时路径问题；使用项目内 .tmp。
- 修复测试错误使用非公开 Details.Builder，改用模拟的系统输入。
- Robolectric 4.14.1 在 service.destroy 时存在 ShadowCallScreeningService/ShadowService 类型冲突；回调单测改为直接创建服务实例，未申请生命周期资源，不把该测试声明为生命周期验收。
- 修复 local.properties 的盘符转义、补齐应用图标。
- 6 条 lint 提示均为固定依赖有更新版本，没有关闭或抑制 lint。版本固定是本轮基线，后续再评估升级。
- 构建仍有 SDK XML 元数据版本提示、测试框架动态 Java agent 警告；测试及构建退出码为 0。

HTML 报告位于 android/app/build/reports/tests/testDebugUnitTest/index.html 和 android/app/build/reports/lint-results-debug.html。XML 测试证据位于 android/app/build/test-results/testDebugUnitTest/。这些是本机构建产物，未提交 Git。

## 尚未完成

两家厂商各 10 次真实来电、回调成功率 100%、P95 < 2 秒、角色申请/失效实际 UI、普通进程死亡后恢复，全部待验收。设备首次连接后失去连接，本轮没有安装 APK 或变更系统角色。真机步骤见 device-validation.md。

完整 MVP 的 Room、联系人、名单、Profile、风险规则、记录反馈等未实现。用户明确排除的所有 AI、云端、语音能力未实现。


## WP-08 更新

构建成功，Android162项、后端46项、导出14项、设备本地回归2项与真实DeepSeek探针1项通过；lint0错误17警告。真实模型输入是合成特征，人工UI与真实来电解释仍待验证。

完整文件职责、命令、限制和原始证据见 [WP08实施报告](wp08-implementation.md)。
