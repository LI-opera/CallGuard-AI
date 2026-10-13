from psycopg.types.json import Jsonb
from server.app.storage import InfrastructureStore
class ExplanationStore(InfrastructureStore):
    async def initialize(self):
        await self.redis.ping()
        async with await self.connect() as c:
            await c.execute("""CREATE TABLE IF NOT EXISTS ai_explanations (
                request_id UUID PRIMARY KEY, call_id UUID NOT NULL, fingerprint CHAR(64) NOT NULL,
                response JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())""")
    async def claim(self,rid,owner):return bool(await self.redis.set(self.prefix+":lock:"+rid,owner,nx=True,ex=90))
    async def get(self,rid):
        async with await self.connect() as c:
            cur=await c.execute("SELECT fingerprint,response FROM ai_explanations WHERE request_id=%s",(rid,))
            return await cur.fetchone()
    async def begin(self,request,digest,response):
        async with await self.connect() as c:
            cur=await c.execute("INSERT INTO ai_explanations(request_id,call_id,fingerprint,response) VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING RETURNING request_id",(request.request_id,request.call_id,digest,Jsonb(response)))
            return await cur.fetchone() is not None
    async def finish(self,rid,response):
        async with await self.connect() as c:
            await c.execute("UPDATE ai_explanations SET response=%s WHERE request_id=%s",(Jsonb(response),rid))
