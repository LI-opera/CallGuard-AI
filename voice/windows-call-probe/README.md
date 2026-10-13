# Windows电话权限探针（开发包）

此探针用于区分“普通脚本被拒绝”和“带应用身份/能力声明仍被拒绝”。仅枚举蓝牙电话、查询状态并按按钮请求设备访问。没有RegisterApp、Connect、拨号、接听、挂断、音频采集或播放代码。不读取号码/联系人/历史。

## 构建与结构

- Probe.cs：.NET Framework Windows Forms宿主；进程内执行PowerShell/WinRT探针，避免启动子powershell.exe丢失应用身份。通过GetCurrentPackageFullName实测身份，并显示在窗口及JSON。
- AppxManifest.xml：CallGuard.WindowsCallProbe，0.1.0.0，x64，独立Publisher CN=CallGuard Local Experiment；声明runFullTrust、phoneLineTransportManagement、phoneCall。没有借用Phone Link身份。
- ../../scripts/check-windows-call-api.ps1（仓库根scripts）：统一探针，构建时复制到build/probe.ps1，不维护两份实现。
- scripts/build-windows-call-probe.ps1（仓库根）：复用系统.NET Framework4编译器与Windows PowerShell程序集，生成build/应用、清单和图标。无新SDK/Python依赖。

```powershell
.\scripts\build-windows-call-probe.ps1
# 需要Windows正常允许开发应用；脚本不会自动更改该设置。
powershell.exe -NoProfile -Command "Add-AppxPackage -Register 'F:/CallBlocking/voice/windows-call-probe/build/AppxManifest.xml' -ErrorAction Stop"
```

build目录被现有**/build/规则忽略。当前生成的是可注册的松散开发包，不是签名商店MSIX，不适用于直接对外分发。不要把XML解析或EXE能运行当成正式打包授权成功。

## 当前结果

编译成功。EXE以--read-only参数运行，退出0并生成host-unpackaged.json，报告UNPACKAGED，证明进程内WinRT工作。正常Add-AppxPackage注册失败0x80073CFF：缺少开发者/旁加载设置。此时没有正式包身份权限测试结果。

开启开发者模式是系统级设置，需用户明确同意；不会自动安装证书或更改蓝牙驱动。安装后从Windows开始菜单启动CallGuard Call Probe，窗口Identity必须显示CallGuard.WindowsCallProbe开头的真实包全名；若显示UNPACKAGED，不继续权限结论。

按钮Read-only check保存packaged-discovery.json，Request system access保存packaged-access.json。保存位置为当前用户LocalAppData/CallGuard/WindowsCallProbe，日志仅含设备名、状态、错误类型/码和包身份。

## 后续判据与移除

只有包身份真实存在、所需能力已安装且RequestAccessAsync结果已取得，才能和此前脚本结果比较。DeniedBySystem保留为失败，不强行注册连接或修改系统权限绕过。Allowed也只证明权限，不能证明双向PCM或自动接听。

移除仅针对本探针：

```powershell
Get-AppxPackage -Name CallGuard.WindowsCallProbe | Remove-AppxPackage
```

不删除Phone Link/其他软件，不更改既有配对。若本次经授权开启开发者模式，实验结束后可恢复此前关闭状态；不要把用户本来已开启的设置关闭。日志默认保留以便核对。


后续实际验证：用户授权开启开发者模式后，独立测试包已注册并通过包身份启动；系统仍返回DeniedBySystem，安装能力声明已核对。此前“尚未安装”属于历史状态。详见 [正式探针报告](../../docs/device-evidence/voice-bluetooth-initial/packaged-probe-report.md)。当前不继续调用注册/连接API或接入AI。开发者模式保持开启，测试包已安装。
