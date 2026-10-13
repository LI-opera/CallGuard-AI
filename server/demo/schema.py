"""Strict contracts for the text-only simulated call."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class Turn(StrictModel):
    role: Literal["caller", "assistant"]
    text: str = Field(min_length=1, max_length=500)


class SimulateRequest(StrictModel):
    turns: list[Turn] = Field(min_length=1, max_length=4)

    @model_validator(mode="after")
    def ordered(self):
        if self.turns[0].role != "caller" or self.turns[-1].role != "caller":
            raise ValueError("The first and last turns must be caller messages")
        if any(a.role == b.role for a, b in zip(self.turns, self.turns[1:])):
            raise ValueError("Turns must alternate")
        if sum(turn.role == "caller" for turn in self.turns) > 2:
            raise ValueError("At most two caller turns")
        return self


Relationship = Literal["commercial", "colleague", "friend", "family", "unspecified"]


class ModelTurn(StrictModel):
    intent: Literal["normal", "marketing", "suspicious", "unknown"]
    claimed_relationship: Relationship
    confidence: float = Field(ge=0, le=1)
    next_step: Literal["ask", "finish"]
    reply: str = Field(min_length=1, max_length=180)
    reason: str = Field(min_length=1, max_length=160)


class SimulateResponse(StrictModel):
    status: Literal["CONTINUE", "FINISHED"]
    action: Literal[
        "ASK_ONE_MORE", "SIMULATED_ALLOW_5M",
        "SIMULATED_BLOCKLIST", "SIMULATED_REVIEW"
    ]
    intent: Literal["normal", "marketing", "suspicious", "unknown"]
    claimed_relationship: Relationship
    identity_verified: Literal[False] = False
    confidence: float
    reply: str
    reason: str
    model: str
    simulation_only: Literal[True] = True


class StartDemoRequest(StrictModel):
    virtual_id: str = Field(pattern=r"^demo-[a-z0-9-]{3,32}$", max_length=37)


class DemoTurnRequest(StrictModel):
    text: str = Field(min_length=1, max_length=500)


class RedialRequest(StrictModel):
    virtual_id: str = Field(pattern=r"^demo-[a-z0-9-]{3,32}$", max_length=37)
