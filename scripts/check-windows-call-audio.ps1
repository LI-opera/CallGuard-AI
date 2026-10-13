param([string]$OutputPath='docs/device-evidence/voice-bluetooth-initial/windows-core-audio-active.json')
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
namespace CallGuardProbe {
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class Enumerator {}
[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IEnum {
 [PreserveSig] int EnumAudioEndpoints(int flow, uint mask, out ICollection collection);
}
[ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface ICollection {
 [PreserveSig] int GetCount(out uint count);
 [PreserveSig] int Item(uint index,out IDevice device);
}
[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IDevice {
 [PreserveSig] int Activate(ref Guid iid,uint context,IntPtr parameters,out IntPtr value);
 [PreserveSig] int OpenPropertyStore(uint mode,out IStore store);
 [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
 [PreserveSig] int GetState(out uint state);
}
[StructLayout(LayoutKind.Sequential)] struct Key { public Guid id; public uint pid; }
[StructLayout(LayoutKind.Explicit, Size=24)] struct Variant { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public IntPtr pointer; }
[ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IStore {
 [PreserveSig] int GetCount(out uint count);
 [PreserveSig] int GetAt(uint index,out Key key);
 [PreserveSig] int GetValue(ref Key key,out Variant value);
}
public class Endpoint { public string Name; public string Direction; public uint State; public string StateName; }
public static class Reader {
 [DllImport("ole32.dll")] static extern int PropVariantClear(ref Variant value);
 public static Endpoint[] Read() {
 var result=new List<Endpoint>(); var enumerator=(IEnum)new Enumerator();
 try {
 for(int flow=0;flow<2;flow++) {
 ICollection collection; Marshal.ThrowExceptionForHR(enumerator.EnumAudioEndpoints(flow,15,out collection));
 try { uint count; Marshal.ThrowExceptionForHR(collection.GetCount(out count));
 for(uint i=0;i<count;i++) {
 IDevice device; Marshal.ThrowExceptionForHR(collection.Item(i,out device));
 try { uint state; Marshal.ThrowExceptionForHR(device.GetState(out state)); IStore store;
 Marshal.ThrowExceptionForHR(device.OpenPropertyStore(0,out store));
 try { var key=new Key {id=new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"),pid=14}; Variant value;
 Marshal.ThrowExceptionForHR(store.GetValue(ref key,out value));
 try { string name=value.vt==31?Marshal.PtrToStringUni(value.pointer):"unknown";
 result.Add(new Endpoint {Name=name,Direction=flow==0?"output":"input",State=state,StateName=state==1?"ACTIVE":state==2?"DISABLED":state==4?"NOTPRESENT":state==8?"UNPLUGGED":"OTHER"});
 } finally { PropVariantClear(ref value); }
 } finally { Marshal.ReleaseComObject(store); }
 } finally { Marshal.ReleaseComObject(device); }
 }
 } finally { Marshal.ReleaseComObject(collection); }
 }
 } finally { Marshal.ReleaseComObject(enumerator); }
 return result.ToArray();
 }
}
}
"@
$result=[ordered]@{checkedAt=(Get-Date).ToUniversalTime().ToString('o');api='Core Audio IMMDevice enumeration only';endpoints=@([CallGuardProbe.Reader]::Read());openedAudioStream=$false;recordedAudio=$false;playedAudio=$false}
$result | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 -LiteralPath $OutputPath
$result | ConvertTo-Json -Depth 5
