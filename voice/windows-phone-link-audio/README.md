# Phone Link 下行音频只读探针

此模块与 UI 接听控制、AI 处理分开。`WasapiLoopbackProbe.cpp` 使用 Windows WASAPI 对选择的播放端开启共享模式回环采集；可用 --role console|multimedia|communications 选择端点，只统计格式、包数、帧数和系统静音标记；不保存 PCM、号码、联系人或通话文本，不向电话发送声音。

构建无需新下载，使用本机 D 盘 Visual Studio C++ 和 Windows SDK，输出只在本项目 F 盘：

    .\scripts\build-wasapi-loopback-probe.ps1
    .\voice\windows-phone-link-audio\build\WasapiLoopbackProbe.exe --output .\docs\device-evidence\phone-link-ui\wasapi-idle-02.json 2 --role console

输出文件必须不存在；时长参数是 1 到 180 秒，可选 --role 选择端点。2026-09-24 的空闲试运行保存于 `wasapi-idle-01.json`：48 kHz、双声道、32 位扩展格式，2 秒得到 200 个包、96,000 帧。非静音标记在空闲状态也出现，不能据此认定包含人声。真实 Phone Link 下行、对方人声识别及独立音轨均未验证。

WASAPI 端点回环采到的是该播放设备的系统混音，可能包含其他程序声音。若进入真实通话验证，先停止其他播放源，并将“用户在电脑听见对方”和“程序采集到同一段声音”分开记录；只有声音对照成立，才把该端点接入语音处理器。电话上行注入仍是独立待解决问题。

新版探针同时保存逐秒 `rms_dbfs` 和 `peak_dbfs` 数值，不保存录音。2026-09-24 第二次空闲报告见 `wasapi-idle-02.json`；第一秒有背景能量，第二秒为 -120 dBFS，说明必须用通话前后对照，不能仅凭非静音标记推断对方声音。


## 2026-09-24 端点核对与有效性

combined-call-01/wasapi-levels.json 使用旧版默认 communications 角色，对应 扬声器 (VB-Audio Virtual Cable)，而用户实际通过 耳机 (SteelSeries Arctis Nova 5) 清楚听见拨打方。该次 -120 dBFS 数据对 Phone Link 下行音频无效，不可用来断言 Phone Link 绕过 WASAPI 或无法采集。原始文件保留。

wasapi-steelseries-idle-01.json 是修正调用前的中间产物：虽然写着 selected_role=console，实际打开的仍是 VB-CABLE，无效。wasapi-steelseries-idle-02.json 和 corrected-endpoint-idle-01/wasapi-levels.json 证实修正后 console 指向 SteelSeries 耳机。steelseries-local-tone-01.json 因测试端缺少 numpy、未播出声音，无效；没有安装新依赖。steelseries-local-tone-02.json 使用 Python 标准库在同一耳机播出 1 秒低音量本地测试音，采集 RMS 由前后的 -120 dBFS 升至约 -43 dBFS，证明耳机回环探针可测本地声音。此结果仍不证明 Phone Link 通话音频可采；需一次端点一致的实际来电对照。


## corrected-call-01：真实来电对照

用户确认 Phone Link 人工接听并在电脑耳机清楚听到拨打方，通话超过 40 秒。探针在 90 秒窗口中确认 selected_role=console、selected_endpoint=耳机 (SteelSeries Arctis Nova 5)，但 packets=0、total_frames=0，所有时间桶 samples=0。这里的 -120 dBFS 是计算下限，不能解释为“捕获到静音音频”。为什么该端点在通话期间没有输出回环包尚未定位；不据此断言 Phone Link 不可能提供可采音频。本地短音已单独证明探针在相同设备可采到播放流。用户无需为同一问题继续重复拨号，后续先离线分析 Windows 音频会话/端点路由。
