"""Local-only development launcher; compatible with psycopg async on Windows."""
import argparse
import asyncio
import logging
import sys
import uvicorn

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--port",type=int,default=8000)
    args=parser.parse_args()
    if sys.platform=="win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    logging.basicConfig(level=logging.INFO,format="%(message)s")
    uvicorn.run("server.app.main:create_app",factory=True,host="127.0.0.1",port=args.port,access_log=False)

if __name__=="__main__":main()
