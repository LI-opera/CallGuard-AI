# Phone Link UI 只读探针

此工具是 Phone Link 接通通道的第一道验证门，只读取 PhoneExperienceHost 主窗口中的交互控件。它不拨号、不接听、不挂断、不读取电话音频，也不调用 AI。

输出仅包含标准化动作类别（answer、reject、hangup、mute、unmute）、控件类型、状态及不可逆元素指纹。原始控件名称、电话号码、联系人名称、AutomationId 和类名不会写入报告。

构建与空闲窗口检查：

    .\scripts\build-phone-link-ui-probe.ps1
    .\voice\windows-phone-link-ui\build\PhoneLinkUiProbe.exe --output .\docs\device-evidence\phone-link-ui\idle-inspect.json

status=ok 只表示成功读取一个 Phone Link 主窗口；空闲状态下 actions 为空是正常结果。真实来电时还需要再运行一次并确认出现 answer/reject，通话接通时确认出现 hangup。在这些证据出现前，不实现或宣称自动接挂断。

本工具按可访问性语义定位控件，不使用固定屏幕坐标。若 Phone Link 版本或语言变化导致动作无法识别，应先扩充已核对的标签映射并重新检查，不能回退到盲点坐标。
真实来电的只读捕获：

    .\scripts\watch-phone-link-ui.ps1 -Output .\docs\device-evidence\phone-link-ui\incoming-inspect-01.json

脚本默认等待 90 秒，只在报告中出现标准化动作后结束；若超时，会保留最后一次空闲快照并以失败退出。输出路径存在时拒绝覆盖，以保留每次原始数据。
若 Phone Link 显示来电而动作仍为空，可同时运行只读窗口归属监听：

    .\scripts\watch-phone-link-windows.ps1 -Output .\docs\device-evidence\phone-link-ui\incoming-windows-01.jsonl -TimeoutSeconds 120

此文件只记录窗口所属进程和句柄变化，不读取任何窗口标题、号码或联系人。来电探针还会保留 `.samples.jsonl` 状态变化记录；单次空闲快照不能证明来电控件已识别。
## 事件监听准备

`PhoneLinkEventProbe.exe` 已加入同一构建脚本，使用 Windows UI Automation 订阅：桌面顶层窗口打开事件，以及 PhoneExperienceHost 窗口子树的结构、名称、可用状态与屏幕可见性变化事件。它只输出事件类别、进程、控件类型和是否有可访问名称，不保存名称文字、窗口标题、号码或音频；不会调用接听/挂断。

空闲冒烟测试：

    .\scripts\build-phone-link-ui-probe.ps1
    .\voice\windows-phone-link-ui\build\PhoneLinkEventProbe.exe --output .\docs\device-evidence\phone-link-ui\incoming-events-01.jsonl --seconds 120

已验证 2 秒空闲运行正常，输出 started/stopped 两条记录。此结果只证明监听可启动和清理，来电事件仍待一次实测。前面三通测试已经足以证明手机端 CallGuard 页面未打开仍可提交 ALLOW；下一次拨号仅用于电脑端事件/媒体通路，不再重复手机端基础验证。

一次真实来电可用 `scripts/capture-phone-link-call-probes.ps1` 并行运行 UI 事件与音量探针；该脚本只写新目录，不拨号、不接听、不保存 PCM。空闲 10 秒联动冒烟：两个子程序退出码均为 0，事件仅 started/stopped，音频探针读取 479,520 帧；它不算真实来电通过。
