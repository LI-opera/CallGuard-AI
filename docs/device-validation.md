# WP-00/01 真机验收操作

范围仅角色申请与固定 ALLOW 探针。BLOCK/SILENCE 未实现、未验证，后续待办。详细核对见 wp00-01-acceptance-audit.md，逐通数据填写 device-matrix.md。实际状态以 [设备矩阵](device-matrix.md) 和 [剩余验收核对](wp01-remaining-acceptance.md) 为准，不把历史步骤文字当作当前结果。

## 准备与留证

两家厂商、Android API >=29、支持筛查角色。每台至少 10 次非隐藏、未存入联系人普通电话作为基准；联系人/隐藏号等系统可能不投递回调，另记观察，不混入基准。
记录实际设备、系统构建号、安装的 versionName/versionCode、APK 哈希、SIM、勿扰/铃声音量、网络及时间。不要根据源码版本直接填写已安装版本。

以下 SERIAL 替换为实际设备序列号；以下为操作示例，不代表自动执行；已有安装与逐项验证详见设备矩阵。
```powershell
$adb='E:\Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb -s SERIAL install -r .\android\app\build\outputs\apk\debug\app-debug.apk
& $adb -s SERIAL shell dumpsys package com.callguard.app
# 另开终端持续采集，仅 CallGuard 标签，保存每次原始行。
& $adb -s SERIAL logcat -v threadtime -s 'CallGuard:I' '*:S' |
    Tee-Object -FilePath '已创建的验收批次目录\callguard-logcat.txt'
```

每次拨号先登记流水号和人工时间，再匹配 eventId。收到回调、提交响应和实际响铃分开记录；不要只保留成功日志。计时和 P95 定义见 device-matrix.md。

## 角色

1. 从未持有角色状态申请，取消，返回应仍未授权；记录系统页面与 App。
2. 再申请，用户手动授予后返回；实际状态应 HELD。
3. 系统设置选择其他筛查 App 或撤销，再返回 CallGuard；应显示未授权/失效。
4. 重新授权后才开始基准来电。

代码只在申请结果返回、Activity 初始化/ON_RESUME 查询。没有后台实时检测；不得把后台无提示记为该能力已实现。

## 基准来电

普通前台/后台、锁屏/离线等条件写明具体场景，正常角色有效时拨入，观察实际响铃和接听。每台 10 次基准保留完整原始数据。
只有有 received 与 responded 同 eventId 才有可计算的应用耗时；日志不能替代人工响铃观察。

## 三类终止分别测试

| 场景 | 操作与前置核实 | 记录/判定 |
|---|---|---|
| 普通后台进程终止 | 角色有效，App 放后台；记录 pidof；执行 am kill；再次 pidof 确认原进程消失；不重开 App，直接拨入 | 是否重新创建进程、回调/响应/响铃分别记录。若进程未死，则未测到恢复，不能填通过 |
| 最近任务划掉 | 从最近任务列表划掉，记录动作前后 PID，不同时执行 kill 或强停；不重开直接拨入 | PID 可能未消失。只归因于划掉场景，不声称完成进程死亡恢复 |
| 设置中强行停止 | 系统应用信息页手动“强行停止”，记录确认、PID、角色及 package stopped 状态；不重开先拨入 | 作为独立限制观察，实际表现待验证。不把未回调泛化为普通后台死亡失败，也不保证自动恢复 |
| 强停后手动重开 | 完成上一场景留证后，明确手动启动 App并重新核实/申请角色，再拨入 | 新增一行；手动恢复与强停后自动恢复严格区分 |

```powershell
& $adb -s SERIAL shell pidof com.callguard.app
# 仅普通后台进程终止场景：
& $adb -s SERIAL shell am kill com.callguard.app
& $adb -s SERIAL shell pidof com.callguard.app
# 根据实际系统输出核实 stopped 状态，保存相关片段：
& $adb -s SERIAL shell dumpsys package com.callguard.app
```

没有实际证据的项目一律待验证；诊断版允许使用已校验私有JSON中保存的原始消息体留证，须注明与实时logcat的区别。完成设备表、原始证据和用户确认前，不进入 WP-02。


## WP-06 一组反馈真机检查（待验证）

已安装0.6.0-wp06(6)。详细前提/步骤见[WP06报告](wp06-implementation.md)。进入01:53:49详情，点击“这是骚扰电话”，确认本地标签后，同一陌生SIM号码拨入一通，导出USER_HARASSMENT及实际决策，最后恢复正常标签。不要用黑名单操作代替标签反馈，不增加多组拨测。

| 设备/系统 | 版本 | 场景 | 时间/事件ID | 收到回调 | 提交/耗时 | 来电界面 | 响铃 | 振动 | 挂断方 | 原始证据 |
|---|---|---|---|---|---|---|---|---|---|---|
| OPPO PKB110 / Android16 API36 | 0.6.0-wp06(6) | 骚扰标签后真实拨入 | 02:25:37.326 / c8bebafa-b1ce-4bf6-afed-26a79a0fb3bf | 是 | BLOCK / 100ms | 否 | 否 | 否 | 拨打方手动挂断 | [记录](device-evidence/wp06-20260918/feedback-call-observation.md) |

耗时为respondedAtMs-receivedAtMs的单调时间差，不是用户感知响铃延迟。不能把“响铃或振动：是”拆成分别通过。每通原始JSON与用户口述分别保留；独立测试库重放不填写到此表。

WP-06本次拨入已完成；用户反馈→下一次真实决策已有一通证据。测试号码已恢复NORMAL标签。


## WP-07 网络探针与本地回归

本阶段不追加SIM拨测。手机RiskApiDeviceTest通过adb reverse向本机FastAPI发送合成token；返回有限中性结果，同requestId在PostgreSQL审计与Redis缓存关联验证。本地ProductionPipelineTest/FeedbackDeviceTest使用独立库回归。见[WP07证据](device-evidence/wp07-20260918/summary.json)。

这些均不是用户真实来电上传或真实云端风险识别证据；生产网关默认关闭。公网/弱网/P95、跨厂商设备仍未验证。


## WP-08 更新

本轮仅一组真实模型网络探针，合成特征LOCAL_SCORE=20：1458ms、unknown/0.2/修正0，手机与PostgreSQL审计一致。不是SIM来电测试，不填写回调/响应/响铃通过。设备自动化验证默认关闭和恢复；人工界面操作待验证（设备最后处于锁屏）。不新增多通拨测。

完整文件职责、命令、限制和原始证据见 [WP08实施报告](wp08-implementation.md)。


WP-08收尾：用户已提供AI结果页面截图；两条历史记录请求与后端审计一致，均READY。详情见[收尾证据](device-evidence/wp08-20260918/closeout/acceptance.md)。页面结果展示已有证据，模型语义质量仍有限制，不能称为语音意图验证。
