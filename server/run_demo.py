"""Local-only text simulation launcher; no Redis/PostgreSQL/phone device."""
import argparse
import asyncio
import os
import sys
from pathlib import Path
import uvicorn


def load_project_env():
    env_file = Path(__file__).resolve().parents[1] / ".env"
    if not env_file.exists():
        return
    allowed = ("CALLGUARD_", "DEEPSEEK_", "MODEL_")
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.startswith(allowed):
            os.environ[key] = value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if args.port != 8765:
        parser.error("The text demo currently uses fixed localhost port 8765")
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    load_project_env()
    uvicorn.run("server.demo.app:create_app", factory=True,
                host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
