> WP-05 最新状态：见 [实施与验证报告](wp05-implementation.md)。依赖未升级，Room schema v4，使用已有系统启动计数与单调时钟接口。

> WP-04 最新状态：见 [实施与真机验收报告](wp04-implementation.md)。依赖未升级；新增 READ_CONTACTS 运行时授权入口，Room schema v3。

> WP-03 最新状态：见 [规则引擎实施报告](wp03-implementation.md)。本轮没有新增或升级依赖。

> WP-02 最新状态：见 [实施与验证报告](wp02-implementation.md)。下文保留 WP-00/01 历史环境/测试记录。

# 环境检查（2026-09-16/17）

| 检查 | 结果 |
|---|---|
| 初始目录 | F:\CallBlocking；只有原始计划书，无代码 |
| Git | 初始不是仓库；本轮 git init，尚无提交 |
| JDK / javac | E:\Android\Jdk21，21.0.12.1 |
| SDK | E:\Android\Sdk；platform 36；Build Tools 35.0.0、36.0.0 |
| Gradle | 本机已安装 8.14.3；已生成相同版本 Wrapper |
| Studio | E:\Android\AndroidStudio；AI-261.26222.65.2613.15948027 |
| adb | platform-tools 37.0.1 |
| Python | 3.12.10；项目 .venv 已创建；隔离验证成功 |
| PATH | 不含 java、javac、gradle、adb、sdkmanager；绝对路径可用 |
| 设备 | 第一次看到一台 PKB110；后续查询无设备，连接状态变化 |

首次 Gradle 报 Unable to establish loopback connection，stacktrace 指向 Windows JDK UnixDomainSockets/PipeImpl。改用项目内短临时目录后 Wrapper 成功；scripts/build.ps1 已封装，不改全局环境。

构建出现 SDK XML version 4/工具支持 version 3 的元数据警告，及原生库无法剥离符号的警告。它们不等同测试失败，也不能忽略真机验收。最终结果见 validation.md。


## WP-06 更新

应用0.6.0-wp06(6)，Room schema5。工具链和依赖未升级；沿用项目.venv（Python标准库工具）、Gradle用户目录和隔离临时目录。143项单元、12项导出、2项设备测试通过；构建/哈希/警告见[WP06报告](wp06-implementation.md)。未新增Python依赖，requirement.txt和requirements.txt保持原内容。


## WP-07 Python与本机服务

Python3.12.10项目.venv；FastAPI0.115.12、Pydantic2.11.4、uvicorn0.34.2、redis6.0.0、psycopg3.2.9、httpx0.28.1、pytest8.3.5、pytest-asyncio0.26.0。直接依赖requirements.txt，完整版本requirements.lock.txt，requirement.txt仍为兼容入口；pip check通过。没有修改系统Python包。

Docker Engine29.4.0；项目容器postgres:17.4-alpine、redis:7.4.2-alpine，监听本机55432/56379。用户修复Docker启动套接字错误后联调成功。Windows psycopg异步通过server.run使用Selector事件循环，启动脚本已接入；.env不入Git，模板.env.example无真实凭据。

Android版本0.7.0-wp07(7)，原Android依赖/JDK/SDK保持不变，数据库schema仍5。API本机8000，非公网部署。完整命令/证据见[WP07报告](wp07-implementation.md)。


## WP-08 更新

应用0.8.0-wp08(8)，Room schema5，Android工具链及Python依赖未升级。.venv继续隔离依赖；.env增加CALLGUARD_AI_*、DEEPSEEK_*，模板提供MODEL_*兼容供应商切换。模型密钥只在服务端，后端访问密钥在手机AndroidKeyStore加密保存。实验配置deepseek-chat，实际响应deepseek-flash。

完整文件职责、命令、限制和原始证据见 [WP08实施报告](wp08-implementation.md)。
