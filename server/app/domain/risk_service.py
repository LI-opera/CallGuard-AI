import hashlib
import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4
from pydantic import ValidationError
from server.app.schemas.risk import RiskRequest, RiskResponse
from server.app.domain.errors import RiskError
from server.app.domain.policy_version import TTL_SECONDS

class NeutralProvider:
    """Implemented baseline: no model or external evidence, hence zero adjustment."""
    async def analyze(self, request: RiskRequest) -> dict:
        return dict(request_id=request.request_id, schema_version="risk-001", risk_adjustment=0,
                    intent="unknown", confidence=0.0, explanation_code="NO_CLOUD_EVIDENCE",
                    expires_at=(datetime.now(timezone.utc)+timedelta(seconds=TTL_SECONDS)).isoformat())

class RiskService:
    def __init__(self, store, provider=None):
        self.store, self.provider = store, provider or NeutralProvider()

    def checked(self, raw, request):
        try:
            response = RiskResponse.model_validate(raw)
            expiry = datetime.fromisoformat(response.expires_at.replace("Z", "+00:00"))
            remaining = (expiry-datetime.now(timezone.utc)).total_seconds()
            if response.request_id != request.request_id or not 0 < remaining <= TTL_SECONDS + 1:
                raise ValueError("identity or expiry mismatch")
            return response
        except (ValidationError, ValueError, TypeError):
            raise RiskError(502, "INVALID_RISK_OUTPUT") from None

    async def analyze(self, request):
        canonical = request.model_dump()
        canonical["features"] = sorted(canonical["features"], key=lambda f:f["code"])
        digest = hashlib.sha256(json.dumps(canonical,sort_keys=True,separators=(",",":")).encode()).hexdigest()
        await self.store.rate_limit()
        cached = await self.store.cached(request.request_id)
        if cached is not None:
            return self.replay(cached, digest, request)
        owner = str(uuid4())
        if not await self.store.claim(request.request_id, owner):
            raise RiskError(429, "REQUEST_IN_PROGRESS")
        try:
            previous = await self.store.audit_get(request.request_id)
            if previous is not None:
                result = self.replay(previous,digest,request)
            else:
                result = self.checked(await self.provider.analyze(request),request)
                saved = await self.store.audit_put(request.request_id,digest,request.policy_version,result.model_dump())
                result = self.replay(saved,digest,request)
            await self.store.cache(request.request_id, {"fingerprint":digest,"response":result.model_dump()})
            return result
        finally:
            await self.store.release(request.request_id,owner)

    def replay(self, previous,digest,request):
        if previous["fingerprint"] != digest:
            raise RiskError(409,"REQUEST_ID_CONFLICT")
        # Expired IDs remain in PostgreSQL: never re-evaluate an old ID as a new request.
        try:
            expiry=datetime.fromisoformat(previous["response"]["expires_at"].replace("Z","+00:00"))
            if expiry <= datetime.now(timezone.utc):
                raise RiskError(409,"REQUEST_ID_EXPIRED")
        except (KeyError,ValueError,TypeError):
            raise RiskError(502,"INVALID_RISK_OUTPUT") from None
        return self.checked(previous["response"],request)
