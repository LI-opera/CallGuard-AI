from datetime import datetime, timezone
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from server.app.domain.policy_version import POLICY_VERSION

class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)

class Feature(StrictModel):
    code: Literal["REPEAT_CALL", "RECENT_FREQUENCY", "LOCAL_SCORE", "USER_HARASSMENT", "LAST_SEEN_AT_MS"]
    value: int = Field(ge=0)

    @model_validator(mode="after")
    def bounds(self):
        maximum = {"REPEAT_CALL": 1000, "RECENT_FREQUENCY": 1000, "LOCAL_SCORE": 100, "USER_HARASSMENT": 1,
                   "LAST_SEEN_AT_MS": int(datetime.now(timezone.utc).timestamp() * 1000)}[self.code]
        if self.value > maximum:
            raise ValueError("feature out of bounds")
        return self

class RiskRequest(StrictModel):
    request_id: str
    number_token: str = Field(pattern=r"^n1:[0-9a-f]{64}$")
    features: list[Feature] = Field(max_length=16)
    policy_version: Literal["wp03-local-v1"]
    deadline_ms: int = Field(ge=1, le=800)

    @field_validator("request_id")
    @classmethod
    def canonical_uuid(cls, value):
        if str(UUID(value)) != value:
            raise ValueError("canonical UUID required")
        return value

    @model_validator(mode="after")
    def unique_features(self):
        if len({f.code for f in self.features}) != len(self.features):
            raise ValueError("duplicate feature code")
        return self

class RiskResponse(StrictModel):
    request_id: str
    schema_version: Literal["risk-001"]
    risk_adjustment: int = Field(ge=-10, le=10)
    intent: Literal["unknown", "marketing", "delivery", "normal"]
    confidence: float = Field(ge=0, le=1)
    explanation_code: Literal["NO_CLOUD_EVIDENCE", "REPEATED_UNANSWERED", "LOCAL_FEATURES_ONLY"]
    expires_at: str

    @field_validator("request_id")
    @classmethod
    def canonical_uuid(cls, value):
        return RiskRequest.canonical_uuid(value)

    @field_validator("expires_at")
    @classmethod
    def utc_timestamp(cls, value):
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if dt.tzinfo is None or dt.utcoffset().total_seconds() != 0:
            raise ValueError("UTC timestamp required")
        return value
