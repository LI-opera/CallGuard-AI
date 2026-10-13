"""Explicit synthetic Android->local API probe; never prints the API key."""
import argparse
import json
import subprocess
from pathlib import Path
import psycopg
import redis

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--adb",required=True)
    parser.add_argument("--serial",required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    env=dict(line.split("=",1) for line in (root/".env").read_text(encoding="utf-8").splitlines() if line.startswith("CALLGUARD_"))
    output=args.output;output.mkdir(parents=True,exist_ok=False)
    adb=[args.adb,"-s",args.serial]
    subprocess.run(adb+["reverse","tcp:8000","tcp:8000"],check=True,capture_output=True)
    result=subprocess.run(adb+["shell","am","instrument","-w","-r","-e","class","com.callguard.app.screening.RiskApiDeviceTest","-e","riskApiKey",env["CALLGUARD_API_KEY"],"com.callguard.app.test/androidx.test.runner.AndroidJUnitRunner"],capture_output=True,timeout=30)
    report=(result.stdout+result.stderr).replace(env["CALLGUARD_API_KEY"].encode(),b"<REDACTED>")
    (output/"device-api-test.txt").write_bytes(report)
    if result.returncode or b"OK (1 test)" not in result.stdout:
        raise RuntimeError("Device probe failed; see report")
    result=subprocess.run(adb+["exec-out","run-as","com.callguard.app","cat","no_backup/wp07-risk-http.json"],capture_output=True,timeout=10)
    if result.returncode:raise RuntimeError("Probe evidence unavailable")
    data=json.loads(result.stdout)
    (output/"device-api-result.json").write_bytes(result.stdout)
    with psycopg.connect(env["CALLGUARD_POSTGRES_DSN"]) as connection:
        rows=connection.execute("SELECT policy_version,response FROM risk_audit WHERE request_id=%s",(data["requestId"],)).fetchall()
    if len(rows)!=1:raise RuntimeError("Expected exactly one audit row")
    with redis.Redis.from_url(env["CALLGUARD_REDIS_URL"],decode_responses=True) as cache:
        cached=json.loads(cache.get("callguard:result:"+data["requestId"]))
    if cached["response"]!=rows[0][1]:raise RuntimeError("Cache/audit mismatch")
    (output/"device-api-audit.json").write_text(json.dumps(dict(requestId=data["requestId"],postgresAuditCount=1,policyVersion=rows[0][0],response=rows[0][1],redisCacheMatchesPostgres=True),indent=2),encoding="utf-8")
    print(json.dumps(data))

if __name__=="__main__":
    try:main()
    except Exception:
        raise SystemExit("Probe not confirmed. Check local server, installed test APK and saved report; credentials suppressed.") from None
