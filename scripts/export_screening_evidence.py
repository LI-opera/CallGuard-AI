"""Read-only export of debug APK evidence, using the project Python stdlib only."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess

PACKAGE = "com.callguard.app"
DIRECTORY = "no_backup/screening-evidence-v1"
NAME = re.compile(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\.json")

def validate(raw, name):
    row = json.loads(raw)
    required = {"schemaVersion", "eventId", "receivedAtMs", "respondedAtMs", "latencyMs",
        "receivedWallTimeMs", "pid", "api", "versionName", "versionCode", "direction",
        "decision", "responseStatus", "receivedLog", "respondedLog"}
    if isinstance(row, dict) and row.get("schemaVersion") == 2:
        required |= {"fallbackUsed", "policyVersion", "matchedRules", "riskScore"}
    if not isinstance(row, dict) or set(row) != required:
        raise ValueError("Unexpected evidence schema")
    if not NAME.fullmatch(name) or row["eventId"] != name[:-5]:
        raise ValueError("Event/file mismatch")
    for key in ["schemaVersion", "receivedAtMs", "respondedAtMs", "latencyMs", "receivedWallTimeMs", "pid", "api", "versionCode"]:
        if type(row[key]) is not int or row[key] < 0:
            raise ValueError("Invalid numeric evidence")
    if row["schemaVersion"] not in (1, 2) or row["decision"] not in ("ALLOW", "SILENCE", "BLOCK") or row["direction"] != "INCOMING" or row["responseStatus"] != "respondToCall_returned":
        raise ValueError("Unexpected response status")
    if row["respondedAtMs"] - row["receivedAtMs"] != row["latencyMs"]:
        raise ValueError("Inconsistent elapsed time")
    if (row["versionName"], row["versionCode"]) not in {("0.1.0-wp01-diag1", 1), ("0.2.0-wp02", 2), ("0.3.0-wp03", 3), ("0.4.0-wp04", 4), ("0.5.0-wp05", 5), ("0.6.0-wp06", 6), ("0.7.0-wp07", 7), ("0.8.0-wp08", 8)}:
        raise ValueError("Unrecognized diagnostic build version")
    if row["schemaVersion"] == 1:
        if row["decision"] != "ALLOW" or row["versionCode"] >= 4:
            raise ValueError("Invalid legacy evidence")
    else:
        if row["versionCode"] not in (4, 5, 6, 7, 8) or type(row["fallbackUsed"]) is not bool or row["policyVersion"] != "wp03-local-v1":
            raise ValueError("Invalid policy metadata")
        rules = row["matchedRules"]
        permitted = {"EMERGENCY_CONTACT", "CONTACT", "ALLOW_RULE", "BLOCK_RULE", "USER_CONFIRMED_SCAM", "TEMP_ALLOW",
            "LOCAL_DATA_UNAVAILABLE", "BASE", "STRANGER", "RECENT_FREQUENCY", "REPEAT_CALL", "USER_CALLED_BACK",
            "ANSWERED_TWICE", "PREVIOUS_REJECTION", "USER_MARKETING", "USER_HARASSMENT", "USER_FAMILY", "INSUFFICIENT_LOCAL_EVIDENCE",
            "POLICY_STANDARD", "POLICY_STRICT", "POLICY_LENIENT", "LOCAL_DEADLINE", "LOCAL_READ_FAILED",
            "LOCAL_QUEUE_FULL", "TIMER_UNAVAILABLE", "CLOUD_TIMEOUT", "CLOUD_BUSY", "CLOUD_UNAVAILABLE", "CLOUD_HTTP_ERROR", "CLOUD_INVALID_RESPONSE", "CLOUD_UNCERTAIN", "CLOUD_ADJUSTMENT"}
        if not isinstance(rules, list) or not rules or any(not isinstance(r, str) or r not in permitted for r in rules):
            raise ValueError("Invalid policy reason")
        if row["riskScore"] is not None and (type(row["riskScore"]) is not int or not 0 <= row["riskScore"] <= 100):
            raise ValueError("Invalid risk score")
    expected_received = f"received eventId={row['eventId']} api={row['api']} receivedAtMs={row['receivedAtMs']} direction=0"
    expected_responded = f"responded eventId={row['eventId']} decision={row['decision']} respondedAtMs={row['respondedAtMs']} latencyMs={row['latencyMs']}"
    if row["receivedLog"] != expected_received or row["respondedLog"] != expected_responded:
        raise ValueError("Log payload does not match permitted fields")
    return row

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial, "exec-out", "run-as", PACKAGE]
    listing = subprocess.run(adb + ["ls", DIRECTORY], capture_output=True, timeout=20)
    if listing.returncode:
        raise SystemExit("Evidence directory unavailable; no conclusion about call callbacks. Check diagnostic APK and run-as.")
    names = listing.stdout.decode("utf-8").splitlines()
    files = sorted(name for name in names if NAME.fullmatch(name))
    args.output.mkdir(parents=True, exist_ok=False)
    manifest = {"exportedAt": datetime.datetime.now().astimezone().isoformat(),
        "package": PACKAGE, "source": DIRECTORY, "ignoredPartialOrUnknownEntries": len(names)-len(files),
        "note": "Records are post-response commits. Absence does not prove no callback; raw bytes preserved. APK hash must be attached separately.", "files": []}
    for name in files:
        result = subprocess.run(adb + ["cat", DIRECTORY + "/" + name], capture_output=True, timeout=20)
        if result.returncode:
            raise SystemExit("Evidence read failed; partial export is not a complete snapshot.")
        row = validate(result.stdout, name)
        (args.output / name).write_bytes(result.stdout)
        manifest["files"].append({"name": name, "sha256": hashlib.sha256(result.stdout).hexdigest(),
            "receivedWallTimeMs": row["receivedWallTimeMs"], "latencyMs": row["latencyMs"]})
    (args.output / "export-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Exported {len(files)} validated records; device files unchanged. This is not a device acceptance result.")

if __name__ == "__main__":
    main()
