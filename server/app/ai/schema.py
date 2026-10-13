from typing import Literal
from pydantic import Field, field_validator, model_validator
from server.app.schemas.risk import StrictModel, Feature, RiskRequest

class ExplainRequest(StrictModel):
    request_id:str
    call_id:str
    call_ended:bool
    features:list[Feature]=Field(max_length=16)
    policy_version:Literal["wp03-local-v1"]
    @field_validator("request_id","call_id")
    @classmethod
    def uuid(cls,value):return RiskRequest.canonical_uuid(value)
    @model_validator(mode="after")
    def validate_context(self):
        if not self.call_ended:raise ValueError("Call end confirmation required")
        if len({f.code for f in self.features})!=len(self.features):raise ValueError("Duplicate features")
        return self

class ModelExplanation(StrictModel):
    intent:Literal["unknown","marketing","delivery","normal"]
    summaryCode:Literal["INSUFFICIENT_EVIDENCE","REPEATED_CALLS","USER_MARKED_HARASSMENT","LOW_LOCAL_RISK","HIGH_LOCAL_RISK"]
    confidence:float=Field(ge=0,le=1)
    riskAdjustment:int=Field(ge=-10,le=10)
