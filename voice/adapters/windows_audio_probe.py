"""Read-only WinMM endpoint/format query. Does not open, record or play audio."""
import argparse
import ctypes as C
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

class WaveFormat(C.Structure):
    _fields_=[("tag",C.c_ushort),("channels",C.c_ushort),("rate",C.c_uint32),
              ("bytes_sec",C.c_uint32),("align",C.c_ushort),("bits",C.c_ushort),("extra",C.c_ushort)]

class InputCaps(C.Structure):
    _fields_=[("mid",C.c_ushort),("pid",C.c_ushort),("version",C.c_uint32),
              ("name",C.c_wchar*32),("formats",C.c_uint32),("channels",C.c_ushort),("reserved",C.c_ushort)]

class OutputCaps(C.Structure):
    _fields_=InputCaps._fields_+[("support",C.c_uint32)]

def probe(name_contains: str):
    if sys.platform!="win32": raise RuntimeError("Windows-only probe")
    lib=C.WinDLL("winmm")
    devices=[]
    for direction, prefix, caps_type in [("input","waveIn",InputCaps),("output","waveOut",OutputCaps)]:
        count=getattr(lib,prefix+"GetNumDevs");count.argtypes=[];count.restype=C.c_uint
        caps_fn=getattr(lib,prefix+"GetDevCapsW")
        caps_fn.argtypes=[C.c_size_t,C.POINTER(caps_type),C.c_uint];caps_fn.restype=C.c_uint
        query=getattr(lib,prefix+"Open")
        query.argtypes=[C.POINTER(C.c_void_p),C.c_uint,C.POINTER(WaveFormat),C.c_size_t,C.c_size_t,C.c_uint]
        query.restype=C.c_uint
        for index in range(count()):
            caps=caps_type();rc=caps_fn(index,C.byref(caps),C.sizeof(caps))
            if rc: raise RuntimeError(f"{prefix}GetDevCaps failed: {rc}")
            if name_contains.casefold() not in caps.name.casefold(): continue
            formats=[]
            for rate in (8000,16000):
                fmt=WaveFormat(1,1,rate,rate*2,2,16,0)
                # WAVE_FORMAT_QUERY=1: no recording/playback handle is opened.
                rc=query(None,index,C.byref(fmt),0,0,1)
                formats.append({"rate":rate,"channels":1,"bits":16,"resultCode":rc,"supportedByQuery":rc==0})
            devices.append({"direction":direction,"index":index,"name":caps.name,"formats":formats})
    return {"checkedAt":datetime.now(timezone.utc).isoformat(),"api":"WinMM format query only",
            "devices":devices,"recordedAudio":False,"playedAudio":False,
            "realDuplexVerified":False,"automaticCallControlVerified":False}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name-contains",required=True,help="Explicit device-name filter; no default device fallback")
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    if not args.name_contains.strip(): parser.error("Nonempty filter required")
    result=probe(args.name_contains)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(result,ensure_ascii=True,indent=2))
    return 0 if {d["direction"] for d in result["devices"]}=={"input","output"} else 2

if __name__=="__main__": raise SystemExit(main())
