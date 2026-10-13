import asyncio
import hashlib
import json
import uuid
from pydantic import ValidationError
from server.app.ai.schema import ModelExplanation
from server.app.domain.errors import RiskError

PROMPT_VERSION="wp08-features-v1"
SYSTEM_PROMPT="""You explain completed call-screening records using only supplied numerical features, never call audio or content.
Return a JSON object with exactly intent, summaryCode, confidence, riskAdjustment.
intent: unknown|marketing|delivery|normal. summaryCode: INSUFFICIENT_EVIDENCE|REPEATED_CALLS|USER_MARKED_HARASSMENT|LOW_LOCAL_RISK|HIGH_LOCAL_RISK.
confidence is a number 0..1; riskAdjustment an integer -10..10. Never return BLOCK, ALLOW, SILENCE, decision, action, phone numbers or extra keys.
Behavioral features alone cannot establish the caller's actual intent: use unknown and confidence below 0.7 if unsupported.
User labels are user feedback, not verified fraud. Prefer riskAdjustment 0 without new independent evidence.
Example JSON: {"intent":"unknown","summaryCode":"INSUFFICIENT_EVIDENCE","confidence":0.2,"riskAdjustment":0}.
"""
class ExplanationService:
    def __init__(self,store,model):self.store,self.model=store,model
    async def explain(self,request):
        payload=request.model_dump()
        payload["features"]=sorted(payload["features"],key=lambda f:f["code"])
        digest=hashlib.sha256(json.dumps(payload,sort_keys=True).encode()).hexdigest()
        await self.store.rate_limit()
        owner=str(uuid.uuid4())
        if not await self.store.claim(request.request_id,owner):raise RiskError(429,"AI_IN_PROGRESS")
        try:
            old=await self.store.get(request.request_id)
            if old:
                if old["fingerprint"]!=digest:raise RiskError(409,"AI_REQUEST_CONFLICT")
                return old["response"]
            # Persist a claim before external cost. Crash never triggers an automatic second model call.
            features=payload["features"]
            summary=hashlib.sha256(json.dumps(features,sort_keys=True).encode()).hexdigest()
            meta={"request_id":request.request_id,"call_id":request.call_id,"provider":self.model.config.provider,
                  "model":self.model.config.model,"prompt_version":PROMPT_VERSION,"policy_version":request.policy_version,
                  "feature_digest":summary,"feature_summary":features}
            initial={**meta,"status":"PENDING","explanation":None,"error_code":None}
            claimed=await self.store.begin(request,digest,initial)
            if not claimed:return (await self.store.get(request.request_id))["response"]
            try:
                async with asyncio.timeout(self.model.config.timeout):
                    # No phone/token/request ID goes to the model provider.
                    raw,actual=await self.model.generate(SYSTEM_PROMPT,json.dumps({"features":features,"policy_version":request.policy_version}))
                def unique(pairs):
                    value={}
                    for key,item in pairs:
                        if key in value:raise ValueError("duplicate key")
                        value[key]=item
                    return value
                parsed=ModelExplanation.model_validate(json.loads(raw,object_pairs_hook=unique))
                response={**meta,"model":actual,"status":"READY","explanation":parsed.model_dump(),"error_code":None}
            except (ValueError,ValidationError,KeyError,TypeError):
                response={**meta,"status":"FAILED","explanation":None,"error_code":"INVALID_MODEL_OUTPUT"}
            except TimeoutError:
                response={**meta,"status":"FAILED","explanation":None,"error_code":"MODEL_TIMEOUT"}
            except Exception:
                response={**meta,"status":"FAILED","explanation":None,"error_code":"MODEL_UNAVAILABLE"}
            await self.store.finish(request.request_id,response)
            return response
        finally:await self.store.release(request.request_id,owner)
