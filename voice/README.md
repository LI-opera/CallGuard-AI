# Voice 接通与处理模块

状态：通道无关的会话边界与离线测试已实现；Phone Link 人工双向通话已验证，但项目程序尚未获得可编程的来电控制或双向电话 PCM。固定WAV探针不是AI语音服务。

## 依赖方向

```text
手机蜂窝通话
  → 通道适配器（当前研究：Phone Link；备选：SIP）
  → CallChannel + VoiceSession + PCM音频帧
  → run_session
  → AudioProcessor（现有：固定WAV；未来：远程AI服务客户端）
  → 同一通道发送声音、等待播放排空、挂断和释放
```

通道层只处理接听/挂断、连接状态、编解码、采样率转换、发送节奏和背压，不导入DeepSeek、ASR、TTS或业务名单规则。处理层只依赖contracts，不导入蓝牙地址、SIP账户、Android类型，不直接修改用户黑名单。未来云端AI进程负责VAD/ASR/对话/LLM/TTS；本轮不把现有数值特征解释伪装成语音智能体。

当前接口是Python进程内协议；跨进程TLS/WebSocket媒体协议、认证、断线与排队上限尚未实现。后续RemoteAudioProcessor适配器承接远程协议，AI服务不应感知蓝牙还是SIP。更换通道仍需验证格式/生命周期与真实声音，不承诺更换设备完全零适配。

## 文件职责与契约

- contracts.py：VoiceSession只包含会话UUID和音频格式，原始号码留在通道/受控身份映射层。AudioFrame为单声道、小端有符号PCM16、8kHz或16kHz、20ms；入/出序列各自从0递增。格式不匹配拒绝，不隐式转换。
- session.py：run_session串接通道和处理器，限制会话时间、验证帧顺序和长度；音频发送结束先drain，finally尝试hangup和aclose；超时/取消也释放，清理失败抛错。submitted_frames只表示接口提交，不证明对方听见。生产适配器必须保证send/drain有背压、远端挂断能使发送/接收结束。
- processors/wav_greeting.py：提前读取最多30秒、4MB的PCM WAV，切成20ms帧并仅为最后一帧补零。它不采集输入、不做ASR，不证明双向通话；禁止把它的输出测试标记为真实语音通过。
- tests/test_session.py：只使用MemoryChannel测试替身，覆盖音频往返保真、错误帧、取消、时限、接听失败、挂断失败、播放排空超时、WAV与格式匹配。

复用根目录.venv与requirements.txt/requirement.txt，未添加Python依赖。模型工厂仍位于server/app/models/factory.py，密钥继续只在项目.env；此包不读取/复制密钥，不调用模型。

## 验证命令

```powershell
.\.venv\Scripts\python.exe -m pytest voice/tests -q --basetemp=.tmp/pytest-voice
```

Phone Link 没有已验证的第三方双向 PCM 接口，因此没有伪造可拨通的项目启动命令。当前已开始独立验证 Phone Link 的 UI 控制面：windows-phone-link-ui 只读枚举语义控件并脱敏记录动作候选，不执行拨号、接听或挂断。只有真实来电状态识别通过后，才增加受控的接听/挂断命令；媒体入口仍作为另一道门单独验证。


Windows只读音频探针：`python -m voice.adapters.windows_audio_probe --name-contains OPPO --output <报告路径>`。只枚举及查询，不是CallChannel实现。PnP设备可见不等于WinMM/WASAPI端点可用，Phone Link成功不等于自建程序可读写音频。

## 本地虚拟音频线探针

可选依赖独立于服务端：`.\.venv\Scripts\python.exe -m pip install -r voice/requirements-audio.txt`。根目录 requirement.txt/requirements.txt 的服务端依赖不变，不要求服务器安装 Windows 音频组件。

运行：`.\.venv\Scripts\python.exe -m voice.adapters.cable_loopback_probe --output-dir docs/device-evidence/phone-link-bridge/local-loopback-02`。每次选择新的目录，避免覆盖原始数据。

只选择名称严格匹配的 WASAPI CABLE Input/Output，拒绝缺失、重复及默认设备回退。发送低幅度 440/880/660Hz 序列，读取虚拟线，按频率顺序和有效窗口验证。使用 48kHz 双声道 PCM16 的设备格式；尚未实现到处理层 16kHz 单声道的转换。此程序不是 CallChannel，不接听/挂断、不读物理麦克风、不联网。保留 sent.wav、received.wav、result.json；本地通过不等于真实电话或 AI 通过。


Phone Link 下行音频的只读 WASAPI 回环探针见 `windows-phone-link-audio/README.md`。空闲采集接口已运行，但尚未证明真实电话声音可采集，也没有解决上行注入。
