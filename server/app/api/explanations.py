import hmac
import asyncio
from fastapi import APIRouter, Request
from pydantic import ValidationError
from server.app.ai.schema import ExplainRequest
from server.app.domain.errors import RiskError
router=APIRouter()
@router.post("/v1/explanations")
async def explain(request:Request):
    if not hmac.compare_digest(request.headers.get("authorization","").encode(),("Bearer "+request.app.state.api_key).encode()):
        raise RiskError(401,"UNAUTHORIZED")
    service=getattr(request.app.state,"ai_service",None)
    if service is None:raise RiskError(503,"AI_DISABLED")
    if request.headers.get("content-type","").split(";")[0]!="application/json":raise RiskError(415,"JSON_REQUIRED")
    raw=bytearray()
    try:
        async with asyncio.timeout(1):
            async for chunk in request.stream():
                raw.extend(chunk)
                if len(raw)>8192:raise RiskError(413,"REQUEST_TOO_LARGE")
        payload=ExplainRequest.model_validate_json(bytes(raw))
    except ValidationError:raise RiskError(422,"INVALID_EXPLANATION_REQUEST") from None
    except TimeoutError:raise RiskError(408,"BODY_TIMEOUT") from None
    try:return await service.explain(payload)
    except RiskError:raise
    except Exception:raise RiskError(503,"AI_STORAGE_UNAVAILABLE") from None
