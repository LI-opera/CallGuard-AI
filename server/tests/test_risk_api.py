import asyncio
import copy
import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4
import httpx
import pytest
from server.app.main import create_app
from server.app.domain.errors import RiskError
from server.app.domain.risk_service import RiskService, NeutralProvider

KEY="test-key-"+"x"*32

class MemoryStore:
    """Test double only; never imported by the production factory."""
    def __init__(self):
        self.audit={};self.results={};self.locks=set();self.count=0;self.limit=100
    async def rate_limit(self):
        self.count+=1
        if self.count>self.limit: raise RiskError(429,"RATE_LIMITED")
    async def cached(self,rid): return copy.deepcopy(self.results.get(rid))
    async def claim(self,rid,owner):
        if rid in self.locks:return False
        self.locks.add(rid);return True
    async def release(self,rid,owner):self.locks.discard(rid)
    async def cache(self,rid,value):self.results[rid]=copy.deepcopy(value)
    async def audit_get(self,rid):return copy.deepcopy(self.audit.get(rid))
    async def audit_put(self,rid,digest,version,response):
        return self.audit.setdefault(rid,{"fingerprint":digest,"response":copy.deepcopy(response)})

@pytest.fixture
def store():return MemoryStore()
@pytest.fixture
def body():return dict(request_id=str(uuid4()),number_token="n1:"+"a"*64,features=[{"code":"LOCAL_SCORE","value":20}],policy_version="wp03-local-v1",deadline_ms=800)

def client(store,provider=None):
    transport=httpx.ASGITransport(app=create_app(RiskService(store,provider),KEY),raise_app_exceptions=False)
    return httpx.AsyncClient(transport=transport,base_url="http://test",headers={"authorization":"Bearer "+KEY})

async def test_success_is_honest_neutral_result(store,body):
    async with client(store) as c:r=await c.post("/v1/risk/analyze",json=body)
    assert r.status_code==200
    assert r.json()["risk_adjustment"]==0 and r.json()["intent"]=="unknown" and r.json()["confidence"]==0
    assert "decision" not in r.json() and len(store.audit)==1

@pytest.mark.parametrize("mutation",[
    {"phone_number":"13812345678"},{"deadline_ms":801},{"deadline_ms":True},{"number_token":"13812345678"},
    {"policy_version":"unknown"},{"request_id":"not-uuid"},{"features":[{"code":"LOCAL_SCORE","value":101}]},
    {"features":[{"code":"LOCAL_SCORE","value":"20"}]},{"features":[{"code":"LOCAL_SCORE","value":True}]},
    {"features":[{"code":"LAST_SEEN_AT_MS","value":9999999999999}]},
    {"features":[{"code":"LOCAL_SCORE","value":2,"phone":"123"}]},
    {"features":[{"code":"LOCAL_SCORE","value":2},{"code":"LOCAL_SCORE","value":3}]},
])
async def test_strict_request_rejects_without_echoing_input(store,body,mutation):
    body.update(mutation)
    async with client(store) as c:r=await c.post("/v1/risk/analyze",json=body)
    assert r.status_code==422 and r.json()=={"error_code":"INVALID_REQUEST"}
    assert not store.audit

async def test_missing_field_and_invalid_json(store,body):
    del body["number_token"]
    async with client(store) as c:
        assert (await c.post("/v1/risk/analyze",json=body)).status_code==422
        assert (await c.post("/v1/risk/analyze",content=b"{broken",headers={"content-type":"application/json"})).status_code==422

async def test_auth_media_and_size_limit(store,body):
    async with client(store) as c:
        assert (await c.post("/v1/risk/analyze",json=body,headers={"authorization":"Bearer wrong"})).status_code==401
        assert (await c.post("/v1/risk/analyze",content=b"x")).status_code==415
        assert (await c.post("/v1/risk/analyze",content=b"x"*8193,headers={"content-type":"application/json"})).status_code==413

async def test_duplicate_id_replays_audit_after_cache_loss(store,body):
    async with client(store) as c:
        first=await c.post("/v1/risk/analyze",json=body)
        store.results.clear()
        second=await c.post("/v1/risk/analyze",json=body)
        assert first.json()==second.json() and len(store.audit)==1
        body["features"][0]["value"]=21
        assert (await c.post("/v1/risk/analyze",json=body)).status_code==409

async def test_expired_id_not_recomputed(store,body):
    async with client(store) as c:
        await c.post("/v1/risk/analyze",json=body)
        store.results.clear()
        store.audit[body["request_id"]]["response"]["expires_at"]=(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat()
        r=await c.post("/v1/risk/analyze",json=body)
        assert r.status_code==409 and r.json()["error_code"]=="REQUEST_ID_EXPIRED"

async def test_limit_and_concurrent_identity(store,body):
    async with client(store) as c:
        store.locks.add(body["request_id"])
        assert (await c.post("/v1/risk/analyze",json=body)).json()["error_code"]=="REQUEST_IN_PROGRESS"
        store.limit=0
        assert (await c.post("/v1/risk/analyze",json=body)).status_code==429

class Slow(NeutralProvider):
    async def analyze(self,request):
        await asyncio.sleep(.2)
        return await super().analyze(request)
async def test_timeout_leaves_no_audit(store,body):
    body["deadline_ms"]=10
    async with client(store,Slow()) as c:r=await c.post("/v1/risk/analyze",json=body)
    assert r.status_code==504 and not store.audit

@pytest.mark.parametrize("mutation",[{"risk_adjustment":11},{"risk_adjustment":True},{"decision":"BLOCK"},{"confidence":1.1},{"intent":"scam"},{"request_id":str(uuid4())},{"expires_at":"bad"}])
async def test_invalid_provider_output_never_enters_audit(store,body,mutation):
    class Invalid(NeutralProvider):
        async def analyze(self,request):
            value=await super().analyze(request);value.update(mutation);return value
    async with client(store,Invalid()) as c:r=await c.post("/v1/risk/analyze",json=body)
    assert r.status_code==502 and not store.audit

async def test_internal_error_is_sanitized(store,body):
    class Broken(NeutralProvider):
        async def analyze(self,request):raise RuntimeError("private-token-phone")
    async with client(store,Broken()) as c:r=await c.post("/v1/risk/analyze",json=body)
    assert r.status_code==500 and r.json()=={"error_code":"INTERNAL_ERROR"}

async def test_logs_contain_only_permitted_metadata(store,body,caplog):
    import logging
    caplog.set_level(logging.INFO,logger="callguard.risk")
    async with client(store) as c:await c.post("/v1/risk/analyze",json=body)
    messages=[r.message for r in caplog.records if r.name=="callguard.risk"]
    assert len(messages)==1 and body["request_id"] in messages[0]
    assert body["number_token"] not in messages[0] and "features" not in messages[0]

async def test_dependency_failure_is_503(store,body):
    from redis.exceptions import ConnectionError
    async def failed():raise ConnectionError("private connection details")
    store.rate_limit=failed
    async with client(store) as c:r=await c.post("/v1/risk/analyze",json=body)
    assert r.status_code==503 and r.json()=={"error_code":"DEPENDENCY_UNAVAILABLE"}

async def test_openapi_has_strict_request_and_response_contract(store):
    async with client(store) as c:r=await c.get("/openapi.json")
    data=r.json()
    assert data["components"]["schemas"]["RiskRequest"]["additionalProperties"] is False
    assert data["paths"]["/v1/risk/analyze"]["post"]["security"]==[{"BearerKey":[]}]
