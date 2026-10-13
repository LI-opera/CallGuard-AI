$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
[ComImport,Guid("45BA127D-10A8-46EA-8AB7-56EA9078943C")] class AppActivationManager {}
[ComImport,Guid("2E941141-7F97-4756-BA1D-9DECDE894A3D"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IAppActivationManager {
 [PreserveSig] int ActivateApplication([MarshalAs(UnmanagedType.LPWStr)] string appId,[MarshalAs(UnmanagedType.LPWStr)] string arguments,uint options,out uint processId);
}
public static class CallGuardLaunch {
 public static uint Start() {
 var manager=(IAppActivationManager)new AppActivationManager();
 try { uint pid; Marshal.ThrowExceptionForHR(manager.ActivateApplication("CallGuard.WindowsCallProbe_8b231esxb4j48!Probe","--probe-access",0,out pid));return pid; }
 finally {Marshal.ReleaseComObject(manager);}
 }
}
"@
[CallGuardLaunch]::Start()
