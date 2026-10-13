"""WP-01 host-only diagnostic capture. Unknown values fail closed; no raw log storage."""
import argparse
import datetime
import json
import pathlib
import re
import subprocess

TAGS = ["Telecom", "TelecomFramework", "ActivityManager", "ActivityTaskManager", "AppsFilter", "OplusAppStartupManager", "OplusProcessManager", "OplusHansManager", "OplusTelecomService", "OplusCallsManager", "OplusCallScreeningService", "CallScreeningService", "CallGuard", "CallGuardCapture", "AndroidRuntime"]
WORDS = set("a an the to of for from with without by in on at is was are not no none null true false and or but because due cannot can could unable failed failure fail error exception permission denial denied rejected allowed allow blocked block skip skipped skipping screening call service bind binding bound unbind unbound found resolve resolved resolving exported component package user locked unlocked keyguard background foreground execution start started starting stop stopped stopping frozen freeze unfreeze timeout timed out connected disconnected connection disconnect connect died dead process reason result unavailable available enabled disabled does do has have been being aborted abort invalid missing empty returned return request requested performing perform match matches matched running inactive active restricted restriction preventing prevented prevent requires required grant granted disallow contacts exists lookup completed complete finished finish selected selecting selection restricted cached dropped ignored waiting cancelled canceled remote security caller callee name number address handle uri contact displayName callerName callerNumber phoneNumber extras message action state pid uid duration ms received responded ignored eventId api receivedAtMs respondedAtMs latencyMs direction decision ALLOW not_incoming screening_diag_v2_ready".split())
WORDS.update("CallScreeningService CallScreeningServiceFilter CallScreeningServiceHelper CallScreeningServiceController IncomingCallFilterGraph RoleManagerAdapter onCreate onBind onUnbind onDestroy onScreenCall screenCall bindService bindServiceAsUser onServiceConnected onServiceDisconnected SecurityException IllegalStateException IllegalArgumentException RuntimeException RemoteException DeadObjectException NullPointerException BackgroundServiceStartNotAllowedException SCREENING_BOUND SCREENING_COMPLETED SCREENING_TIMED_OUT FILTERING_INITIATED FILTERING_COMPLETED FILTERING_TIMED_OUT isDeviceLocked isUserUnlocked isInContacts shouldScreenCall skipFiltering".split())
LINE = re.compile(r"^(\d{2}-\d{2})\s+([\d:.]+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s*(.*)$")
LEX = re.compile(r"com\.callguard\.app(?:/\.screening\.CallGuardScreeningService)?|[A-Za-z_][A-Za-z_0-9.$/]*|[^\s]")
# Mask value-bearing fields and quoted strings before vocabulary matching.
PRIVATE = re.compile(r"\b(?:caller|callee|name|number|address|handle|uri|contact|displayName|callerName|callerNumber|phoneNumber)\s*[=:]\s*(?:\"[^\"]*\"|'[^']*'|[^,;\n]+)", re.I)
QUOTED = re.compile(r"\"[^\"]*\"|'[^']*'")

def sanitize(line):
    match = LINE.match(line)
    if not match:
        return None
    date, time, pid, tid, level, tag, body = match.groups()
    tag = tag.strip()
    if tag not in TAGS:
        return None
    safe = QUOTED.sub(" REDACTED ", PRIVATE.sub(" REDACTED ", body))
    tokens = []
    unknown = 0
    for token in LEX.findall(safe):
        if token in WORDS or token == "com.callguard.app" or token == "com.callguard.app/.screening.CallGuardScreeningService" or token in "=:,;()[]{}!":
            tokens.append(token)
        else:
            unknown += 1
            if not tokens or tokens[-1] != "<redacted>":
                tokens.append("<redacted>")
    row = dict(date=date, time=time, pid=int(pid), tid=int(tid), level=level, tag=tag,
               context=" ".join(tokens), redacted_tokens=unknown, lossy=True)
    if tag == "CallGuard":
        for key in ["api", "receivedAtMs", "respondedAtMs", "latencyMs", "direction"]:
            value = re.search(r"\b" + key + r"=(\d{1,16})\b", body)
            if value:
                row[key] = int(value.group(1))
        event = re.search(r"\beventId=([0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12})\b", body)
        if event:
            row["eventId"] = event.group(1)
    if tag == "CallGuardCapture" and body.strip() == "screening_diag_v2_ready":
        row["probe"] = body.strip()
    return row

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation prevents mixing or overwriting earlier sessions.
    with args.output.open("x", encoding="utf-8", buffering=1) as output:
        def emit(value):
            output.write(json.dumps(value, ensure_ascii=True) + "\n")
        emit(dict(started=datetime.datetime.now().astimezone().isoformat(), schema=2,
                  policy="ordered fixed vocabulary; unknown values masked; lossy; raw never stored", tags=TAGS))
        process = subprocess.Popen([args.adb, "-s", args.serial, "logcat", "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime", "-T", "1"] + [tag + ":V" for tag in TAGS] + ["*:S"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
        count = 0
        try:
            for line in process.stdout:
                row = sanitize(line)
                if row is not None:
                    count += 1
                    emit(row)
        except KeyboardInterrupt:
            pass
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                code = process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                code = process.wait()
            emit(dict(stopped=datetime.datetime.now().astimezone().isoformat(), exit_code=code, saved_rows=count))

if __name__ == "__main__":
    main()
