# 蓝牙路线首轮：模块隔离与环境检查

2026-09-18。用户选择本机接听、蓝牙连接电脑，并要求接通模块与AI线上处理分离。实现范围为统一音频/通话接口、会话执行器、固定WAV探针和离线测试，不进入完整ASR/TTS/Voice Agent或名单自动写入。

## 实际环境

Windows识别Realtek Bluetooth Adapter（OK），配对条目包括OPPO Find X8；配对不等于项目程序能够控制通话或读取双向音频。Linux 蓝牙实验后来被用户撤销，相关实现、依赖与下载均已删除，不再作为本报告的后续路线。

## 模块划分已落地

详见[voice/README](../voice/README.md)。CallChannel处理电话与媒体设备，AudioProcessor处理通道无关的PCM。run_session处理时限、排空和清理；WavGreeting是无需LLM的固定音频探针。协议类明确是接口，不是已实现的蓝牙功能。pytest.ini增加voice/tests，默认测试可发现新用例。requirements与.env保持原有版本和内容，无新增全局依赖。

当前没有线上语音AI服务、WebSocket媒体传输或可用的 Phone Link 程序化通道适配器。后续人工 Phone Link 证据见本报告补充章节。

## Windows保留连接的下一条验证路线

已安装Phone Link（Microsoft.YourPhone）1.26072.255.0，存在dotnet命令，但未检测到它当前已建立通话连接。Microsoft说明[Phone Link支持电脑接打手机电话](https://support.microsoft.com/en-us/windows/apps/make-and-receive-phone-calls-from-your-pc)。可先由用户打开“手机连接/Phone Link”的通话页，做一通手动接听、双向声音及挂断检查；此时不运行AI，也不修改CallGuard角色或规则。若当前规则会BLOCK/SILENCE测试号码，需先明确使用已有允许的测试来电，不能把筛查阻止误判为蓝牙故障。

Phone Link成功只证明设备与系统可进行蓝牙通话，不代表我们的程序已获得音频。下一门槛是Windows适配器能否取得来电事件、接挂断控制以及可读写的双向音频流。候选是[PhoneLineTransportDevice](https://learn.microsoft.com/en-us/uwp/api/windows.applicationmodel.calls.phonelinetransportdevice?view=winrt-26100)，仍需验证应用权限/打包及音频端点；不把控制API存在当作PCM可用。普通[AudioPlaybackConnection](https://learn.microsoft.com/en-us/windows/uwp/audio-video-camera/enable-remote-audio-playback)只是接收设备播放音频，不能代替双向通话链路。

若Windows无法给自建处理器暴露双向PCM，记录阻塞，不用UI自动点击假装完成通道适配，再讨论其他电话接入方式。

## 证据与结果

[环境原始报告](device-evidence/voice-bluetooth-initial/environment.json)、[测试XML](device-evidence/voice-bluetooth-initial/tests.xml)。12项离线测试通过；没有再次运行Android构建（本轮未修改Android）。后端兼容回归另见同目录combined-tests.log。不启动付费模型请求、不增加SIM拨测、不提交Git。

结论：模块边界及执行器已实现并测试，真实蓝牙接通与AI通话均待验证。下一步首先是Windows现有通话能力检查，不要求购买设备或移走现有蓝牙连接。


## Phone Link人工验证补充

用户提供呼叫页和“在电脑上通话/正在呼叫”截图，明确确认正常打通，并确认电脑能听到对方、对方能听到电脑麦克风。此证据确认Phone Link人工通话和双向声音，不证明自动接听来电或自建程序收发音频。截图含原始电话号码，仅引用会话附件，不复制到公开项目证据中。

只读检查发现PnP中有OPPO Find X8 Hands-Free HF Audio输入/输出条目。但执行Windows WinMM格式探针时没有枚举到OPPO端点；同一API能枚举到SteelSeries耳机和NVIDIA输出，不能把这些设备误当作手机通话流。查询时通话是否仍活动未确认，不能推断为Windows永久禁止访问，也不能把PnP存在当作端点可用。

新增voice/adapters/windows_audio_probe.py只查询WaveIn/WaveOut端点和8/16kHz PCM16格式，使用WAVE_FORMAT_QUERY，不打开设备、不录音、不播放、不使用默认设备回退。退出码2表示没有同时找到目标输入/输出。WinMM是旧API，此结果不是WASAPI或全部Windows能力的最终结论；生产适配器尚未完成。

命令：`.\.venv\Scripts\python.exe -m voice.adapters.windows_audio_probe --name-contains OPPO --output docs/device-evidence/voice-bluetooth-initial/windows-audio-query.json`。

下一步让一通测试电话在Phone Link里保持接通，再核对活动端点；若仍未暴露，则检查WASAPI/Windows通话音频路由，不能直接接DeepSeek或用环境麦克风冒充对端音频。

原始查询见windows-audio-query.json；人工结果见phone-link-observation.json。没有改变蓝牙配对或默认录放设备，没有新增模型调用。


## 保持通话期间的交叉检查

用户明确“已接通，保持中”，并提供计时00:09的在电脑上通话截图。期间运行WinMM查询和新增scripts/check-windows-call-audio.ps1（Core Audio IMMDevice全状态枚举）。两者均未找到OPPO音频端点；Core Audio活动端点为SteelSeries耳机输入/输出及NVIDIA数字输出。PnP中的OPPO条目不能据此当作普通应用可打开的音频设备。

此结论仅限端点枚举：没有激活IAudioClient或采集/播放音频，不宣称证明所有Windows接口不可用，也不否定用户已确认的Phone Link双向通话。探针最初编译括号和集合IID错误已修正，成功查询后才保存正式Core Audio报告。

原始证据：windows-audio-query-active.json、windows-core-audio-active.json、active-call-summary.json。手机截图包含真实号码，继续仅引用会话附件。检查后已通知用户可挂断，不要求长时间保持或重复拨测。

下一技术门槛是Windows专用通话访问/音频路由能力。若采用Phone Link加虚拟音频路由，只能先作为依赖第三方软件的媒体桥接实验；自动来电事件与接挂断控制仍要独立证明。不得直接把桌面混音或电脑麦克风当成隔离的来电者音频。当前未安装虚拟声卡、未更改默认音频设备、未读取通话音频、未移交蓝牙。


## Windows专用接口探针

设备发现成功，通话存储入口返回对象，当前PowerShell进程RequestAccessAsync返回DeniedBySystem，未注册或连接。当前没有包身份/phoneLineTransportManagement声明，不能据此否定正式打包应用。完整命令、原始证据和下一门槛见[接口验证报告](device-evidence/voice-bluetooth-initial/windows-call-api-report.md)。独立程序通话控制和双向PCM仍待验证，不重复拨测。


## 具有应用身份的探针准备

已创建voice/windows-call-probe/Probe.cs与AppxManifest.xml，使用系统.NET Framework编译器构建。进程内执行WinRT，日志使用系统API核实真实包身份；不会用子PowerShell进程冒充包身份。独立EXE只读执行成功。正常注册包遇到0x80073CFF，开发者模式/旁加载条件未满足，已向用户申请系统设置变更，在答复前不更改。详见[构建与移除说明](../voice/windows-call-probe/README.md)。


后续实际验证：用户授权开启开发者模式后，独立测试包已注册并通过包身份启动；系统仍返回DeniedBySystem，安装能力声明已核对。此前“尚未安装”属于历史状态。详见 [正式探针报告](device-evidence/voice-bluetooth-initial/packaged-probe-report.md)。当前不继续调用注册/连接API或接入AI。开发者模式保持开启，测试包已安装。
