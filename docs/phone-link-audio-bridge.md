# Phone Link 独立音频桥接：首轮准备

日期：2026-09-18。用户选择 Phone Link 接听＋独立音频桥接。当前仅完成设备检查和官方驱动准备，未实现或验证媒体桥接。

## 路由与模块边界

- 接通：用户在 Phone Link 手动接听、手动挂断，沿用当前 Windows 蓝牙连接。
- 下行候选：Phone Link → 当前耳机播放端 → WASAPI loopback → 本地媒体适配器 → AudioProcessor。实际可采集性待验证；端点级 loopback 会混入同端点其他程序声音，首轮关闭其他播放源，不上传音频。
- 上行候选：固定问候 WAV → 程序播放到 CABLE Input（播放设备）→ CABLE Output（录音设备）→ Phone Link 的麦克风 → 远端。Phone Link 是否遵循所选录音设备必须实测，不能由虚拟线安装成功推断。
- 只使用一条虚拟线承担上行；下行不要再送进这条线，否则可能形成反馈。不要打开录音设备的“侦听此设备”。
- 媒体适配器负责端点选择、重采样、PCM 帧、缓冲和关闭；在线处理器继续只消费 voice/contracts.py 的音频契约。密钥仅在服务端 .env。
- 不把手动接听包装成 CallChannel.answer 已实现；不把释放音频设备包装成电话 hangup 已实现。本轮也不直接接入 run_session 的自动挂断流程。

## 实际检查

运行 scripts/check-windows-call-audio.ps1，报告位于 device-evidence/phone-link-bridge/endpoints-before.json。活动输出为 SteelSeries Arctis Nova 5 与 NVIDIA DigitalOutput；活动输入为 SteelSeries 麦克风。无 CABLE 虚拟端点。没有打开音频流，也没有变更默认设备。

## 已准备的系统安装项

官方来源：https://vb-audio.com/Cable/
下载：https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip
本地安装程序：F:/CallBlocking/.tmp/phone-link-bridge-driver/pack45/VBCABLE_Setup_x64.exe
SHA-256：734C35DFA6D98F48782A451633CEB471166EC70D60482FD89A1123D0EE3C4F41
Get-AuthenticodeSignature：Valid，签名者 BUREL VINCENT Entrepreneur individuel。
Windows 10/11 x64 驱动目录文件签名为 Microsoft Windows Hardware Compatibility Publisher，Valid。完整签名结果见 device-evidence/phone-link-bridge/driver-signatures.json。

安装包已下载解压，尚未运行。安装是新增系统音频驱动，需要管理员权限；厂家要求安装后重启。此前对开发者模式与电话权限探针的授权不包含这个驱动，因此本轮单独确认。确认前不安装，不重启。开发者模式不是此音频线的前置条件。

## 安装后的最小验证顺序（待执行）

1. 安装前记录默认播放/录音/通信设备；安装后检查是否被安装器改变，并明确实验路由及恢复值。保持现有蓝牙配对。
2. 先做本地短音频回环，确认程序可向 CABLE Input 播放、从 CABLE Output 读取；此项不证明蜂窝通话可用。
3. 再只做一组真实来电：手动在 Phone Link 接听，远端说约定短句，程序本地采集；程序播放固定问候，远端确认听见；手动挂断。记录会话 ID、时间、端点、格式、采集/提交结果、双方听音反馈。
4. 两个方向有实际证据后，再接 ASR/在线 AI/TTS。不以音量非零代替内容正确，不以成功提交代替远端听见。
5. 自动接听、自动挂断、号码与会话关联、再拨放行、自动名单更新仍未实现。

回退：先关闭媒体程序，将录音/通信设备恢复为安装前记录值；若不保留驱动，使用官方安装程序的移除功能，按提示重启。虚拟驱动不随本项目代码分发。

## 重启后核验（2026-09-18）

用户已授权安装并报告完成重启。实际枚举证据：device-evidence/phone-link-bridge/defaults-after-reboot.json。

- CABLE Input、CABLE In 16ch（播放）与 CABLE Output（录音）均 ACTIVE，驱动端点已可见。
- 与 defaults-before-install.json 比较：Console/Multimedia 默认播放从 SteelSeries 耳机变为 CABLE Input；Communications 默认播放仍为 SteelSeries 耳机。
- 三种角色的默认录音仍为 SteelSeries 麦克风。
- 下一步先恢复普通默认播放为 SteelSeries 耳机。尚未修改录音设备，尚未采集/播放测试音，尚未验证真实电话桥接。
- 上文“尚未运行安装”是安装前记录，当前已被本节实际安装后证据更新。

## 默认设备恢复与一次本地回环（2026-09-18）

defaults-restored.json 证实默认播放与录音的 Console/Multimedia/Communications 三种角色均恢复为 SteelSeries。没有改动默认设备。

新增 voice/adapters/cable_loopback_probe.py（实际虚拟端点音频读写及频率验证）、voice/tests/test_cable_loopback_probe.py（拒绝静音/直流/不完整序列与错误端点）、voice/requirements-audio.txt（独立可选依赖）。只在 .venv 安装 sounddevice 0.5.6、cffi 2.1.1、pycparser 3.0，没有升级服务端依赖。

命令：python -m pytest voice/tests/test_cable_loopback_probe.py -q --basetemp=.tmp/pytest-cable；2 passed。
命令：python -m voice.adapters.cable_loopback_probe --output-dir docs/device-evidence/phone-link-bridge/local-loopback-01；退出 0。

实际 48kHz/双声道/PCM16 回环读到 117600 帧；440、880、660Hz 各检测到 8 个有效 50ms 窗口，顺序正确；inputStatuses 为空，outputUnderflow=false。local-loopback-01 内保留发送和接收 WAV 与 JSON，未覆盖旧证据。结果仅限本地虚拟线；不证明设备重采样链路、Phone Link 双向媒体或自动电话控制。

下一步：在真实电话前，将 CABLE Output 设为实验默认录音/通信设备，播放仍用耳机；一次通话验证程序发送的音频是否被对端听见。恢复目标仍为 SteelSeries 麦克风。下行耳机 loopback 采集适配器尚未实现，不提前宣称双向桥接完成。

## 真实通话前路由检查

用户报告“已设置”后重新读取 defaults-before-call.json：所有默认录音角色仍为 SteelSeries 麦克风，CABLE Output 尚未成为默认输入。原因未确认，未拨打或播放测试音。已重新打开录音设置指导核对。

使用系统已安装中文 System.Speech 离线语音生成 .tmp/phone-link-greeting/greeting.wav（48kHz、双声道 PCM16），只生成文件，没有播放或访问模型服务。该固定问候仅用于对端听音验证，不是 AI 对话功能。

## 第二次通话前配置核验

defaults-before-call-02.json：三种默认输入角色均为 CABLE Output；三种默认输出角色均为 SteelSeries，路由配置已生效。新增 cable_playback_probe.py，严格指定 WASAPI CABLE Input，限制固定 48kHz 双声道 PCM16 WAV 最多 15 秒；拒绝默认设备回退与覆盖报告。greeting-preflight.json 已完成 check-only 预检，问候长 9.3995 秒，未播放。真实电话对端听见与否仍待用户观察，未标记通过。

## 首次真实电话播放未通过

用户确认拨打方没有听到问候。greeting-call-01.json 记录程序提交 451176 帧、播放结束、无欠载；greeting-call-01-observation.json 单独记录用户听音失败，不改写原始程序日志。

只读会话快照 sessions-no-remote-audio.json 未观察到 Phone Link 活动会话，也没有 CABLE Output 上的活动采集会话。快照时间的通话持续状态未确认，且会话枚举不覆盖所有实现路径，因此不能断言 Phone Link 不支持虚拟麦克风。需先确认通话仍接通、音频在电脑侧、通话未静音，再定位路由；暂不重复播放或切换方案。

## 第二通保持接通时的只读检查

用户确认“已接通，保持中”后取得 sessions-active-call-02.json。共享 Core Audio 会话枚举仍未显示 Phone Link 的活动播放/采集会话，PhoneExperienceHost 进程存在。未播放、未采集音频。该结果仅表示当前探针未观察到会话，不能据此判断电话没有音频，也不能确认其实际麦克风。下一步用同一通电话的人声对照，检查物理耳机麦克风是否仍被使用，不再要求重复拨打。

## 物理麦克风对照与重启

用户确认第二通电话里，对电脑耳机麦克风说话可在拨打方听见。结合默认输入为 CABLE Output 的快照，说明本次更改默认输入没有使电话上行采用虚拟线；仍需排除旧进程缓存或应用独立设备选择，不能直接断言虚拟线不兼容。

用户要求继续后，检查发现 PhoneExperienceHost 仍为原 PID 17536（13:48:08 启动）。已只终止该 Phone Link 进程并通过 Get-StartApps 返回的 AppID 重新启动，没有解绑蓝牙、重启系统或修改音频默认设备。新进程记录见 phone-link-restarted.json。已打开 Windows 应用音量设置，应用专用输入选项仍待界面核对。

## 重启 Phone Link 后的真实电话结果：未通过

Phone Link 新进程建立后，用户确认重新接通。greeting-call-after-restart-01.json 记录 2026-09-18 06:15:32Z 起播放固定问候 9.3995 秒，提交 451176 帧，outputUnderflow=false。用户明确反馈“没有声音”，单独保存到 greeting-call-after-restart-01-observation.json。此后未再次播放。

再次快照确认默认录音的所有角色均为 CABLE Output，默认播放均为 SteelSeries；共享 Core Audio 枚举仍未观察到活动会话。该枚举无法说明 Phone Link 底层实际使用的音频实现，不能从无会话推断无通话。

结论边界：本地虚拟线实际回环通过，但“Windows 默认虚拟麦克风 → Phone Link → 对端”的这套配置两次真实验证均未听见问候，重启应用未解决。此前同通话的物理耳机麦克风人声对照可被对端听见。因此问题集中于电话上行如何选取/接入音频，尚未定位具体 API/驱动原因；不是已有证据支持的 AI 或规则模块故障。不能宣称 Phone Link 在所有机器上都不能桥接。

停止重复来电测试，不盲目升高音量或修改 AI。下一轮应先离线核对通话专用音频接入能力，拿到可实施的通道方案后再安排一次验证。当前实验默认输入仍为虚拟线，应恢复为 SteelSeries 麦克风（默认设备和默认通信设备）以免影响其他语音应用；本轮仅打开设置，没有自动改写默认值。

## 恢复确认与无通话资料核查（2026-09-18）

用户报告已恢复后，defaults-restored-after-experiment.json 实际确认 SteelSeries 播放与麦克风均持有 Console、Multimedia、Communications 三类默认角色。本轮无电话、无音频采集或播放、无驱动修改。

### 可确认与不可确认

- Windows PhoneLineTransportDevice 官方方法表提供连接、注册、访问请求、路由状态等，未列出 PCM 音频读写接口。此前本机具备包身份的访问探针仍被 DeniedBySystem 拒绝；即便解决该权限，仍不能推断获得 PCM 媒体通道。
- Microsoft 的 HFP bypass 文档属于音频驱动与 Bluetooth HFP 驱动的硬件/驱动集成说明，不是 Phone Link 的应用插件文档。本机使用了何种内部音频图仍未知，不能把 bypass 当成已证实根因。
- 此次检索未找到微软为 Phone Link 公布的第三方通话 PCM 注入接口；这是检索边界，不是“证明 Windows 上绝不可能”。暂不将 Phone Link + 默认虚拟麦克风作为已可实施的项目主通道，也不继续反复改变音量、重装虚拟线或重复拨号。

### 下一步建议及前置条件

Linux 蓝牙路线已撤销。下一步返回 Phone Link 路线，先讨论能否通过受支持的 Windows 接口取得来电控制与隔离的双向媒体；在没有真实媒体通道时，不把桌面混音、物理麦克风或本地虚拟线回环当作真实 SIM AI 接听证据。

### 一手资料

- https://learn.microsoft.com/en-us/uwp/api/windows.applicationmodel.calls.phonelinetransportdevice?view=winrt-26100
- https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/bluetooth-hfp-bypass-audio-streaming

## 实验组件清理（2026-09-19）

用户确认清理后，已通过 Remove-AppxPackage 卸载当前用户 CallGuard.WindowsCallProbe，查询确认不再安装。根据 developer-mode-before.json，实验前 AllowDevelopmentWithoutDevLicense 值不存在；通过 UAC 删除实验添加的该值，退出码 0，并确认恢复为不存在。没有删除整个 AppModelUnlock 键，也没有修改其他开发设置。

已重新核对 VB-CABLE 官方 x64 安装器签名 Valid 及原 SHA-256，再以管理员方式打开。驱动卸载仍待在官方界面点击 Remove Driver，不能把打开卸载程序标记为卸载成功；之后按厂家提示重启。没有自动重启。Phone Link、蓝牙配对、Android 应用、Docker、项目文件和 .venv 均保留。清理核对记录见 device-evidence/phone-link-bridge/cleanup-status.json。
