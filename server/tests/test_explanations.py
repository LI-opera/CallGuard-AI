import json
import asyncio
from types import SimpleNamespace
from uuid import uuid4
import pytest
import httpx
from server.app.ai.schema import ExplainRequest
from server.app.ai.service import ExplanationService, PROMPT_VERSION
from server.app.models.factory import ModelConfig, create_model
from server.app.main import create_app
from server.app.domain.errors import RiskError
from server.tests.test_risk_api import MemoryStore, KEY

class AiStore(MemoryStore):
    async def get(self,rid):return self.audit.get(rid)
    async def begin(self,request,digest,response):
        if request.request_id in self.audit:return False
        self.audit[request.request_id]={"fingerprint":digest,"response":response};return True
    async def finish(self,rid,response):self.audit[rid]["response"]=response

class Model:
    config=SimpleNamespace(provider="deepseek",model="test-only",timeout=.05)
    def __init__(self,output=None):
        self.output=output or dict(intent="unknown",summaryCode="INSUFFICIENT_EVIDENCE",confidence=.2,riskAdjustment=0)
        self.calls=0;self.input=None
    async def generate(self,system,user):
        self.calls+=1;self.input=user
        return json.dumps(self.output),"test-model-version"

def request():return ExplainRequest(request_id=str(uuid4()),call_id=str(uuid4()),call_ended=True,features=[],policy_version="wp03-local-v1")
async def test_valid_result_is_audited_and_replay_does_not_call_model():
    store=AiStore();model=Model();service=ExplanationService(store,model);req=request()
    result=await service.explain(req);again=await service.explain(req)
    assert result==again and model.calls==1 and result["status"]=="READY"
    assert result["model"]=="test-model-version" and result["prompt_version"]==PROMPT_VERSION
    assert req.request_id not in model.input and req.call_id not in model.input
    assert "number_token" not in model.input

@pytest.mark.parametrize("update",[{"decision":"BLOCK"},{"riskAdjustment":11},{"confidence":1.1},{"confidence":"0.9"},{"summaryCode":"call this phone"},{"intent":"fraud"}])
async def test_invalid_model_fields_discarded_and_not_retried(update):
    model=Model();model.output.update(update);store=AiStore();service=ExplanationService(store,model);req=request()
    result=await service.explain(req)
    assert result["status"]=="FAILED" and result["explanation"] is None
    assert (await service.explain(req))["status"]=="FAILED" and model.calls==1

async def test_timeout_audited_without_automatic_model_retry():
    class Slow(Model):
        async def generate(self,system,user):
            self.calls+=1;await asyncio.sleep(.2)
    model=Slow();store=AiStore();service=ExplanationService(store,model);req=request()
    result=await service.explain(req)
    assert result["error_code"]=="MODEL_TIMEOUT" and result["explanation"] is None
    await service.explain(req);assert model.calls==1

async def test_duplicate_model_keys_discarded():
    class Duplicate(Model):
        async def generate(self,system,user):return '{"intent":"normal","intent":"unknown"}',"test"
    result=await ExplanationService(AiStore(),Duplicate()).explain(request())
    assert result["status"]=="FAILED"

async def test_id_conflict_and_durable_pending_never_double_bill():
    store=AiStore();model=Model();service=ExplanationService(store,model);req=request()
    await service.explain(req)
    changed=req.model_copy(update={"call_id":str(uuid4())})
    with pytest.raises(RiskError):await service.explain(changed)
    store.audit[req.request_id]["response"]["status"]="PENDING"
    assert (await service.explain(req))["status"]=="PENDING" and model.calls==1

@pytest.mark.parametrize("provider",["deepseek","openai_compatible"])
async def test_factory_posts_json_with_no_secret_in_representation(provider):
    config=ModelConfig(provider,"fixture-model","https://api.deepseek.com","test-secret-value",1)
    def handle(request):
        data=json.loads(request.content)
        assert data["model"]=="fixture-model" and data["response_format"]=={"type":"json_object"}
        return httpx.Response(200,json={"model":"actual","choices":[{"finish_reason":"stop","message":{"content":"{}"}}]})
    model=create_model(config,httpx.MockTransport(handle))
    assert "test-secret-value" not in repr(config)
    assert await model.generate("JSON","{}") == ("{}","actual")

def test_factory_env_switches_without_code_change(monkeypatch):
    monkeypatch.setenv("CALLGUARD_AI_PROVIDER","openai_compatible")
    monkeypatch.setenv("MODEL_BASE_URL","https://example.com/v1")
    monkeypatch.setenv("MODEL_MODEL","replacement")
    monkeypatch.setenv("MODEL_API_KEY","test")
    assert create_model().config.model=="replacement"
    monkeypatch.setenv("CALLGUARD_AI_PROVIDER","unknown")
    with pytest.raises(ValueError):create_model()

async def test_endpoint_requires_end_confirmation_and_rejects_private_fields():
    service=ExplanationService(AiStore(),Model())
    app=create_app(service=object(),api_key=KEY,ai_service=service)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url="http://test",headers={"authorization":"Bearer "+KEY}) as c:
        body=request().model_dump();body["call_ended"]=False
        assert (await c.post("/v1/explanations",json=body)).status_code==422
        body["call_ended"]=True;body["phone"]="13812345678"
        response=await c.post("/v1/explanations",json=body)
        assert response.status_code==422 and "13812345678" not in response.text
        del body["phone"]
        assert (await c.post("/v1/explanations",json=body)).json()["status"]=="READY"
        app.state.ai_service=None
        assert (await c.post("/v1/explanations",json=body)).json()["error_code"]=="AI_DISABLED"
