import asyncio
import hmac
import logging
import time
from fastapi import APIRouter, Request
from redis.exceptions import RedisError
from psycopg import Error as PostgresError
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from server.app.schemas.risk import RiskRequest
from server.app.domain.errors import RiskError

router=APIRouter()
logger=logging.getLogger("callguard.risk")

@router.post("/v1/risk/analyze")
async def analyze(request:Request):
    auth=request.headers.get("authorization","")
    if not hmac.compare_digest(auth.encode(),("Bearer "+request.app.state.api_key).encode()):
        raise RiskError(401,"UNAUTHORIZED")
    if request.headers.get("content-type","").split(";")[0].strip().lower()!="application/json":
        raise RiskError(415,"JSON_REQUIRED")
    started=time.monotonic()
    raw=bytearray()
    try:
        async with asyncio.timeout(.8):
            async for chunk in request.stream():
                raw.extend(chunk)
                if len(raw)>8192:
                    raise RiskError(413,"REQUEST_TOO_LARGE")
    except TimeoutError:
        raise RiskError(408,"BODY_TIMEOUT") from None
    try:
        payload=RiskRequest.model_validate_json(bytes(raw))
    except ValidationError:
        # Never echo offending fields, body, token, or phone-shaped input.
        raise RiskError(422,"INVALID_REQUEST") from None
    start=time.monotonic()
    try:
        async with asyncio.timeout(max(0,payload.deadline_ms/1000-(time.monotonic()-started))):
            result=await request.app.state.service.analyze(payload)
        return JSONResponse(result.model_dump())
    except TimeoutError:
        raise RiskError(504,"RISK_TIMEOUT") from None
    except RiskError:
        raise
    except (RedisError, PostgresError):
        raise RiskError(503,"DEPENDENCY_UNAVAILABLE") from None
    except Exception:
        raise RiskError(500,"INTERNAL_ERROR") from None
    finally:
        logger.info("request_id=%s policy_version=%s elapsed_ms=%d",payload.request_id,payload.policy_version,int((time.monotonic()-start)*1000))
