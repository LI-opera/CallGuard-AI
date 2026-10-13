import asyncio
import json
import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from redis.asyncio import Redis
from server.app.domain.errors import RiskError

class InfrastructureStore:
    """Redis is disposable; PostgreSQL owns durable identity and response audit."""
    def __init__(self, redis_url, postgres_dsn, limit=60, prefix="callguard"):
        self.prefix=prefix
        self.redis=Redis.from_url(redis_url,decode_responses=True,socket_connect_timeout=.2,socket_timeout=.2)
        self.dsn=postgres_dsn
        self.limit=limit

    async def connect(self):
        return await psycopg.AsyncConnection.connect(self.dsn,connect_timeout=2,row_factory=dict_row)

    async def initialize(self):
        await self.redis.ping()
        async with await self.connect() as conn:
            await conn.execute("""CREATE TABLE IF NOT EXISTS risk_audit (
                request_id UUID PRIMARY KEY, fingerprint CHAR(64) NOT NULL,
                policy_version TEXT NOT NULL, response JSONB NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now())""")

    async def close(self):
        await self.redis.aclose()

    async def rate_limit(self):
        count=await self.redis.eval("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",1,self.prefix+":rate")
        if count>self.limit:
            raise RiskError(429,"RATE_LIMITED")

    async def cached(self,rid):
        data=await self.redis.get(self.prefix+":result:"+rid)
        return json.loads(data) if data else None

    async def claim(self,rid,owner):
        return bool(await self.redis.set(self.prefix+":lock:"+rid,owner,nx=True,ex=2))

    async def release(self,rid,owner):
        try:
            async with asyncio.timeout(.05):
                await self.redis.eval("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",1,self.prefix+":lock:"+rid,owner)
        except (Exception, asyncio.CancelledError):
            # Lease expiry releases abandoned work. PostgreSQL unique key prevents duplicate facts.
            pass

    async def cache(self,rid,value):
        await self.redis.set(self.prefix+":result:"+rid,json.dumps(value),ex=60)

    async def audit_get(self,rid):
        async with await self.connect() as conn:
            result=await conn.execute("SELECT fingerprint,response FROM risk_audit WHERE request_id=%s",(rid,))
            return await result.fetchone()

    async def audit_put(self,rid,digest,version,response):
        async with await self.connect() as conn:
            await conn.execute("INSERT INTO risk_audit(request_id,fingerprint,policy_version,response) VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING",(rid,digest,version,Jsonb(response)))
            result=await conn.execute("SELECT fingerprint,response FROM risk_audit WHERE request_id=%s",(rid,))
            return await result.fetchone()
