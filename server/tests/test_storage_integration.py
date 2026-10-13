"""Requires actual Redis/PostgreSQL; no in-memory fallback permitted."""
import os
from uuid import uuid4
import pytest
from server.app.storage import InfrastructureStore
from server.app.domain.risk_service import RiskService
from server.app.schemas.risk import RiskRequest
from server.app.domain.errors import RiskError

@pytest.mark.skipif(os.environ.get("CALLGUARD_INTEGRATION")!="1",reason="Real Redis/PostgreSQL not enabled")
async def test_real_audit_survives_cache_loss_and_identity_is_unique():
    prefix="callguard-test-"+uuid4().hex
    store=InfrastructureStore(os.environ["CALLGUARD_REDIS_URL"],os.environ["CALLGUARD_POSTGRES_DSN"],prefix=prefix)
    await store.initialize()
    request=RiskRequest(request_id=str(uuid4()),number_token="n1:"+"a"*64,features=[],policy_version="wp03-local-v1",deadline_ms=800)
    service=RiskService(store)
    try:
        first=await service.analyze(request)
        await store.redis.delete(prefix+":result:"+request.request_id)
        second=await service.analyze(request)
        assert first==second
        async with await store.connect() as conn:
            cursor=await conn.execute("SELECT count(*) AS total FROM risk_audit WHERE request_id=%s",(request.request_id,))
            assert (await cursor.fetchone())["total"]==1
        conflicting=request.model_copy(update={"number_token":"n1:"+"b"*64})
        with pytest.raises(RiskError) as err:await service.analyze(conflicting)
        assert err.value.status==409
        assert await store.claim(str(uuid4()),"owner")
    finally:
        async with await store.connect() as conn:
            await conn.execute("DELETE FROM risk_audit WHERE request_id=%s",(request.request_id,))
        keys=[key async for key in store.redis.scan_iter(match=prefix+":*")]
        if keys:await store.redis.delete(*keys)
        await store.close()
