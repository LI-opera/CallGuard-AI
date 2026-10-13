# WP-07 Risk API 与有界客户端

2026-09-18。本阶段实现本机可运行的服务端、Redis短期幂等/缓存、PostgreSQL审计、Android限时客户端及可注入协调器。服务器与手机合成请求已实际联调。真实来电仍默认只走本地规则；未启用真实号码token上传、LLM、云端风险库、反馈云同步或自动诈骗识别。未进入WP08，未提交Git。

## 接口与真实能力

`POST /v1/risk/analyze`，Bearer密钥（本机.env），请求只允许request_id、number_token、features、policy_version、deadline_ms。UUID使用规范字符串，token必须n1:加64位hex；最多16个不重复特征；deadline为1..800整数。Feature仅允许REPEAT_CALL、RECENT_FREQUENCY、LOCAL_SCORE、USER_HARASSMENT、LAST_SEEN_AT_MS，并各自校验范围；时间特征不得在未来。未知字段、bool冒充整数、字符串数字、非法JSON均拒绝，不回显输入。

使用实际本地策略版本`wp03-local-v1`，替代计划书示例`policy-001`。响应risk-001，修正限制[-10,10]，confidence为0..1有限数，intent/explanation_code枚举，UTC有效期60秒，禁止最终动作字段。上限是本阶段明确选定的保守边界，非经过训练的概率模型。

首版NeutralProvider始终返回修正0、intent=unknown、confidence=0、NO_CLOUD_EVIDENCE。这是明确的无模型基线，不伪造示例中的marketing或0.86置信度。客户端低于0.7保持本地分数。高置信度有效修正也不能创建额外本地风险证据，不能覆盖联系人/黑白名单/临时允许等直接决策。

错误码：401 UNAUTHORIZED；415 JSON_REQUIRED；413 REQUEST_TOO_LARGE；422 INVALID_REQUEST；429 RATE_LIMITED/REQUEST_IN_PROGRESS；409 REQUEST_ID_CONFLICT/REQUEST_ID_EXPIRED；504 RISK_TIMEOUT；502 INVALID_RISK_OUTPUT；503 DEPENDENCY_UNAVAILABLE；500 INTERNAL_ERROR。日志只输出已验证的requestId、策略版本、耗时；不打印输入、token或密钥。默认关闭HTTP访问日志。

## 数据与幂等

Redis保存60秒固定窗口限流计数、2秒带owner的NX锁以及60秒结果缓存。单开发实例/单API密钥；不是多租户鉴权实现。锁释放比较owner，异常依赖锁到期恢复。

PostgreSQL risk_audit是事实库：request_id主键、请求规范化摘要SHA-256、策略版本、校验后响应JSONB、创建时间。成功结果先写审计再缓存。Redis丢失后同ID从PostgreSQL恢复相同响应，不能再算一次；同ID不同请求409，已到期ID409，调用者必须为新分析生成新ID。并发冲突以PostgreSQL唯一键和摘要核对兜底，不靠Redis锁单独保证一致性。审计不存原始号码、号码token或完整特征，只存不可逆请求摘要与有限响应。

数据库与缓存故障返回错误，客户端保留本地结果。真实存储测试使用随机测试命名空间与请求ID，并只清理自己插入的测试审计行，不清空其他表/Redis。

## Android边界

RiskApiClient仅允许HTTPS，调试调用可显式允许回环HTTP；不跟随重定向。共享入口绝对单调截止，最多800ms，至多2次尝试；只有I/O故障或429/500/502/503/504可重试，第二次沿用同请求ID。401、非法JSON、过期/越界结果直接放弃。读取最大16KiB；有限工作线程，超时取消并断开连接。OS调度不能保证零误差实时返回，但没有两次各800ms的叠加预算。

ScreeningCoordinator完成本地决策后才可调用注入的CloudRiskGateway，传入本次回调剩余预算。截止定时器独立运行；云端慢/失败时保留已经算出的本地动作和分数，不把本地SILENCE/BLOCK重新降成未知ALLOW。原子提交守卫阻止迟到结果二次响应，降级原因持久化为CLOUD_*码。

生产ScreeningRuntime未注入云端网关，未配置地址/密钥；当前真实电话不等待网络、不上传。手机RiskApiDeviceTest显式使用合成token和adb reverse，只测试客户端网络通路，不读取用户电话/联系人/Room。这与真实来电云查询启用是不同证据。

新增INTERNET普通权限，无新增敏感运行时权限。debug网络安全配置仅允许127.0.0.1/localhost明文，release不含此放行。没有把API密钥写入APK。

## 文件职责

| 文件（相对项目根） | 作用 |
|---|---|
| server/app/main.py | FastAPI工厂、启动依赖、健康检查、错误输出、OpenAPI |
| server/app/api/risk.py | 鉴权、体积限制、严格JSON、总预算、脱敏日志 |
| server/app/schemas/risk.py | 请求、特征、响应严格Pydantic模型 |
| server/app/domain/policy_version.py | 策略/结构版本与TTL定义 |
| server/app/domain/risk_service.py | 中性基线、校验、指纹、幂等重放 |
| server/app/domain/errors.py | 有限错误码异常 |
| server/app/storage.py | 真实Redis与PostgreSQL实现，无内存生产替身 |
| server/run.py | 本机启动；Windows使用Selector事件循环兼容psycopg |
| server/app/**/__init__.py、server/__init__.py | Python包入口 |
| server/compose.yaml | 独立项目容器、回环端口与PostgreSQL持久卷 |
| server/tests/test_risk_api.py | 契约/超时/限流/格式错误/幂等/脱敏测试；仅这里有存储替身 |
| server/tests/test_storage_integration.py | 真实Redis/PostgreSQL缓存丢失与审计唯一性 |
| server/tests/conftest.py、pytest.ini | Windows事件循环和项目测试配置 |
| requirements.txt、requirement.txt、requirements.lock.txt | 固定直接依赖、兼容入口和实装完整锁定版本 |
| .env.example、本地.gitignore排除的.env | 配置模板与本机随机凭据；真实凭据不入库 |
| scripts/start-risk-server.ps1 | 可选启动容器并运行API；只监听本机 |
| scripts/probe-risk-device.py | 安全读取本地密钥，执行手机合成请求并关联审计/缓存 |
| scripts/test-risk-server.ps1 | 默认API测试或-Integration真实存储测试 |
| android/.../cloud/RiskApiClient.kt | 有界HTTP客户端、重试、严格响应解析 |
| android/.../cloud/CloudRiskGateway.kt | 脱敏请求与有限修正，本地直接规则优先 |
| android/.../screening/ScreeningCoordinator.kt | 可选网关、共享截止、本地结果保留 |
| android/app/src/main/AndroidManifest.xml | INTERNET权限 |
| android/app/src/debug/AndroidManifest.xml、res/xml/debug_network_security.xml | 仅调试回环网络配置 |
| android/app/build.gradle.kts | 0.7.0-wp07(7)，未升级Android依赖 |
| android/app/src/test/.../cloud/RiskApiClientTest.kt | 真实TCP HTTP测试服务覆盖请求与故障 |
| android/app/src/test/.../screening/ScreeningCoordinatorTest.kt | 云端迟到/异常不覆盖本地、不重复响应 |
| android/app/src/androidTest/.../screening/RiskApiDeviceTest.kt | 手机→真实API的合成请求探针 |
| scripts/export_screening_evidence.py、scripts/test_export_screening_evidence.py | WP07版本及CLOUD_*原因码校验 |
| README.md、docs/environment.md、docs/architecture.md、docs/device-validation.md | 当前能力和运行、验证说明 |
| docs/device-evidence/wp07-20260918/ | APK、构建/测试/设备请求与审计证据 |

## 验证与修复记录

Android157项单元测试、Python导出13项、服务端31项含真实存储测试；具体最终结果和APK哈希见[汇总](device-evidence/wp07-20260918/summary.json)。构建包含assembleDebug、testDebugUnitTest、lintDebug、assembleDebugAndroidTest；lint0错误14警告，主要为既有版本/KAPT与写法提示。

真机本地ProductionPipelineTest、FeedbackDeviceTest在独立测试数据库回归2项；网络RiskApiDeviceTest使用合成数据。请求ID可在device-api-result.json和device-api-audit.json关联，明确simCallTest=false。本阶段没有新增SIM拨测，不从HTTP200推断真实拦截能力或阶段全部验收。

曾遇到并修复：Android编译环境不提供JDK HttpServer模块，测试改用ServerSocket提供真实HTTP；debug网络配置需显式includeSubdomains；Windows psycopg需要Selector而非Proactor事件循环。失败日志保留。首次数据库驱动失败日志含本机连接凭据，公开工作区报告已替换为REDACTED；原始只留在.gitignore排除的.tmp中。

Docker先出现dockerInference、随后Secrets Engine套接字不可访问；自动重命名失败、未重置Docker。用户修复后实际启动成功，Redis/PostgreSQL均健康，原先暂缓的联调已补做。

## 运行与检查

```powershell
# 从 F:\CallBlocking 执行；已有 .venv/.env 无需重新创建
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
# 新机器先复制.env.example到.env并配置随机密钥/密码
.\scripts\start-risk-server.ps1 -StartInfrastructure
# 另一个项目PowerShell窗口
.\scripts\test-risk-server.ps1 -Integration
.\.venv\Scripts\python.exe -m unittest discover -s scripts -p test_export_screening_evidence.py -v
.\scripts\build.ps1 -JdkPath E:/Android/Jdk21 -SdkPath E:/Android/Sdk -Tasks @(':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebugAndroidTest')
```

服务 http://127.0.0.1:8000；文档/docs；健康/health/live；Redis仅127.0.0.1:56379，PostgreSQL仅127.0.0.1:55432。现有API进程PID保存于证据server-pid.txt，容器名callguard-wp07-*。停止项目容器使用`docker compose --env-file .env -f server/compose.yaml stop`，保留审计卷；不执行down -v。终端前台API用Ctrl+C停止。

手机联调安装debug与androidTest APK，执行adb reverse tcp:8000 tcp:8000，再单独执行RiskApiDeviceTest并从.env提供riskApiKey参数。不要复制真实密钥进共享命令/日志；实际执行时用脚本内读取传参。生产筛查不因此被启用云端。只读查询PostgreSQL对应request_id并核对Redis缓存是完整联调证据，不能只看响应200。

## 仍未实现或未验证

- 公网部署、TLS终止、生产鉴权/多租户、审计保留清理策略与容量压测尚未完成；当前是回环开发服务。
- 真实来电云端调用开关、端点/凭据管理及用户授权流程尚未开启；当前仅可注入并验证的适配器。
- 风险库、LLM/AI解释属于后续阶段；正常/骚扰反馈仍LOCAL_ONLY，没有反馈同步API。
- 本次手机网络样本不能证明公网弱网/P95，也不能替代跨厂商真机矩阵。
- 不自动进入WP08，不自动提交Git。

参考实现核对：[Pydantic严格校验](https://docs.pydantic.dev/latest/concepts/strict_mode/)、[FastAPI生命周期](https://fastapi.tiangolo.com/advanced/events/)、[Redis SET NX/EX](https://redis.io/docs/latest/commands/set/)。本项目具体行为以本轮源码和测试结果为准。


## 最终构建证据

最终APK SHA-256：`d21eec979b1b14ea6d887234bfac7c55ef851fcd3bb85d4a6708eed81a10dacb`（与手机base.apk一致）。最终手机探针requestId `627ccaef-a320-403a-9ce8-5326069ac5a0`，合成请求耗时 87ms，返回0修正/unknown，并与PostgreSQL一条审计、Redis缓存匹配。最终本地设备回归2/2通过，筛查角色仍正常。

复跑合成探针（API与两个容器运行，手机已安装匹配的debug/androidTest APK）：

```powershell
.\.venv\Scripts\python.exe scripts/probe-risk-device.py --adb E:/Android/Sdk/platform-tools/adb.exe --serial HM9L89ROUKUCJF9P --output .tmp/wp07-new-probe
```

输出目录须是新目录，以保留每次原始证据。早期78ms探针留在证据目录根；最终版本87ms探针单独在final-device-probe，不混用版本。上述耗时是合成API往返，不是SIM回调/响铃延迟。
