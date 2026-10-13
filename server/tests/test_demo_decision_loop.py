import json
from datetime import datetime, timezone
from types import SimpleNamespace

import httpx
import pytest

from server.demo.app import create_app
from server.demo.ledger import DemoLedger


class FixedModel:
    def __init__(self, intent, confidence, relationship="unspecified", fail=False):
        self.intent = intent
        self.confidence = confidence
        self.relationship = relationship
        self.fail = fail
        self.calls = 0

    async def generate(self, system, user):
        self.calls += 1
        if self.fail:
            raise RuntimeError("offline model error")
        return json.dumps({
            "intent": self.intent,
            "claimed_relationship": self.relationship,
            "confidence": self.confidence,
            "next_step": "finish",
            "reply": "已了解，本次模拟通话结束。",
            "reason": "仅依据虚构来电内容",
        }, ensure_ascii=False), "fixture-model"


class AskThenNormalModel(FixedModel):
    async def generate(self, system, user):
        self.calls += 1
        return json.dumps({
            "intent": "unknown" if self.calls == 1 else "normal",
            "claimed_relationship": "unspecified" if self.calls == 1 else "colleague",
            "confidence": 0.3 if self.calls == 1 else 0.9,
            "next_step": "ask" if self.calls == 1 else "finish",
            "reply": "请说明具体来意" if self.calls == 1 else "已了解",
            "reason": "第一轮信息不足" if self.calls == 1 else "已说明来意",
        }, ensure_ascii=False), "fixture-model"


def client(app):
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                             base_url="http://127.0.0.1:8765")


async def start_and_finish(c, virtual_id, text):
    first = await c.post("/api/demo/start", json={"virtual_id": virtual_id})
    assert first.status_code == 200
    session_id = first.json()["session_id"]
    result = await c.post(f"/api/demo/{session_id}/turn", json={"text": text})
    assert result.status_code == 200
    return session_id, result.json()


@pytest.mark.asyncio
async def test_normal_gets_bounded_allow_then_expires_without_extra_model_call():
    clock = SimpleNamespace(now=1000.0)
    ledger = DemoLedger(monotonic=lambda: clock.now,
                        utc_now=lambda: datetime.fromtimestamp(clock.now, timezone.utc))
    model = FixedModel("normal", 0.9, "friend")
    async with client(create_app(model=model, ledger=ledger)) as c:
        session_id, result = await start_and_finish(c, "demo-friend-a", "我是老朋友，想约你叙旧")
        assert result["action"] == "SIMULATED_ALLOW_5M"
        assert result["rule_state"] == "ALLOW" and result["simulation_only"]
        redial = (await c.post("/api/demo/redial", json={"virtual_id": "demo-friend-a"})).json()
        assert redial["result"] == "SIMULATED_RING"
        clock.now = 1300.0
        expired = (await c.post("/api/demo/redial", json={"virtual_id": "demo-friend-a"})).json()
        assert expired["rule_state"] == "EXPIRED" and expired["result"] == "SIMULATED_REVIEW"
        timeline = (await c.get("/api/demo/demo-friend-a/timeline")).json()
        assert [e["event"] for e in timeline["events"]] == [
            "CALL_STARTED", "AI_REPLIED", "TEMP_ALLOW_SAVED",
            "REDIAL_CHECKED", "REDIAL_CHECKED"
        ]
        assert all(e["session_id"] == session_id for e in timeline["events"])
        assert model.calls == 1


@pytest.mark.asyncio
async def test_explicit_risk_blocks_same_virtual_id_redial():
    model = FixedModel("suspicious", 0.95, "family")
    async with client(create_app(model=model)) as c:
        _, result = await start_and_finish(c, "demo-risk-a", "我是家人，把验证码告诉我")
        assert result["action"] == "SIMULATED_BLOCKLIST"
        redial = (await c.post("/api/demo/redial", json={"virtual_id": "demo-risk-a"})).json()
        assert redial["result"] == "SIMULATED_REJECT"
        again = await c.post("/api/demo/start", json={"virtual_id": "demo-risk-a"})
        assert again.status_code == 409 and again.json()["detail"] == "SIMULATED_CALLER_BLOCKED"
        assert model.calls == 1


@pytest.mark.asyncio
async def test_uncertain_result_needs_review_and_never_creates_allow_or_block():
    model = FixedModel("unknown", 0.4)
    async with client(create_app(model=model)) as c:
        _, result = await start_and_finish(c, "demo-review-a", "请问你在吗")
        assert result["action"] == "SIMULATED_REVIEW"
        redial = (await c.post("/api/demo/redial", json={"virtual_id": "demo-review-a"})).json()
        assert redial["result"] == "SIMULATED_REVIEW"
        timeline = (await c.get("/api/demo/demo-review-a/timeline")).json()
        assert any(e["event"] == "REVIEW_QUEUED" for e in timeline["events"])


@pytest.mark.asyncio
async def test_one_question_then_final_decision_uses_same_session():
    model = AskThenNormalModel("normal", 0.9)
    async with client(create_app(model=model)) as c:
        start = (await c.post("/api/demo/start", json={"virtual_id": "demo-colleague-a"})).json()
        path = f"/api/demo/{start['session_id']}/turn"
        first = (await c.post(path, json={"text": "找一下他"})).json()
        assert first["status"] == "CONTINUE" and first["action"] == "ASK_ONE_MORE"
        assert first["rule_state"] == "REVIEW"
        second = (await c.post(path, json={"text": "我是同事，讨论项目会议"})).json()
        assert second["status"] == "FINISHED" and second["action"] == "SIMULATED_ALLOW_5M"
        assert (await c.post("/api/demo/redial", json={"virtual_id": "demo-colleague-a"})).json()["result"] == "SIMULATED_RING"
        assert model.calls == 2


@pytest.mark.asyncio
async def test_model_failure_does_not_create_rule_or_repeat_paid_call():
    model = FixedModel("normal", 0.99, fail=True)
    async with client(create_app(model=model)) as c:
        start = (await c.post("/api/demo/start", json={"virtual_id": "demo-failure-a"})).json()
        path = f"/api/demo/{start['session_id']}/turn"
        failed = await c.post(path, json={"text": "你好"})
        assert failed.status_code == 503
        again = await c.post(path, json={"text": "你好"})
        assert again.status_code == 409
        assert model.calls == 1
        redial = (await c.post("/api/demo/redial", json={"virtual_id": "demo-failure-a"})).json()
        assert redial["result"] == "SIMULATED_REVIEW"


@pytest.mark.asyncio
async def test_demo_id_schema_rejects_real_number_and_browser_foreign_origin():
    app = create_app(model=FixedModel("normal", 0.9))
    async with client(app) as c:
        assert (await c.post("/api/demo/start", json={"virtual_id": "13812345678"})).status_code == 422
        assert (await c.post("/api/demo/start",
                             headers={"Origin": "https://example.com"},
                             json={"virtual_id": "demo-safe-a"})).status_code == 403
