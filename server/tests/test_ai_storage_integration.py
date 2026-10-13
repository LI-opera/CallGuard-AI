import os
from uuid import uuid4
import pytest
from server.app.ai.storage import ExplanationStore
from server.app.ai.service import ExplanationService
from server.tests.test_explanations import Model, request
@pytest.mark.skipif(os.environ.get("CALLGUARD_INTEGRATION")!="1",reason="Real Redis/PostgreSQL not enabled")
async def test_ai_audit_replays_without_second_model_call():
    prefix="ai-test-"+uuid4().hex
    store=ExplanationStore(os.environ["CALLGUARD_REDIS_URL"],os.environ["CALLGUARD_POSTGRES_DSN"],prefix=prefix)
    await store.initialize();req=request();model=Model()
    try:
        first=await ExplanationService(store,model).explain(req)
        second=await ExplanationService(store,model).explain(req)
        assert first==second and model.calls==1
        assert (await store.get(req.request_id))["response"]["model"]=="test-model-version"
    finally:
        async with await store.connect() as c:await c.execute("DELETE FROM ai_explanations WHERE request_id=%s",(req.request_id,))
        keys=[key async for key in store.redis.scan_iter(match=prefix+":*")]
        if keys:await store.redis.delete(*keys)
        await store.close()
