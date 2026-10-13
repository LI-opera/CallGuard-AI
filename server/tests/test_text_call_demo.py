import json
from types import SimpleNamespace
import httpx
import pytest
from server.demo.app import create_app
from server.demo.logic import simulate
from server.demo.schema import SimulateRequest


class FakeModel:
    config = SimpleNamespace(model="fixture")

    def __init__(self, answers):
        self.answers = list(answers)
        self.inputs = []

    async def generate(self, system, user):
        self.inputs.append(json.loads(user))
        return json.dumps(self.answers.pop(0), ensure_ascii=False), "fixture-model"


def answer(intent="unknown", confidence=0.3, next_step="ask", reply="请问有什么具体事宜？"):
    return dict(intent=intent, claimed_relationship="unspecified", confidence=confidence, next_step=next_step,
                reply=reply, reason="只依据模拟来电者陈述")


@pytest.mark.asyncio
async def test_two_turns_ask_then_simulated_temporary_allow():
    model = FakeModel([answer(), answer("normal", 0.92, "finish", "请稍后再拨。")])
    first = await simulate(SimulateRequest(turns=[{"role": "caller", "text": "我来送东西"}]), model)
    assert first.status == "CONTINUE" and first.action == "ASK_ONE_MORE"
    second = await simulate(SimulateRequest(turns=[
        {"role": "caller", "text": "我来送东西"},
        {"role": "assistant", "text": first.reply},
        {"role": "caller", "text": "我是预约的快递员，到了楼下"},
    ]), model)
    assert second.action == "SIMULATED_ALLOW_5M" and second.simulation_only is True
    assert len(model.inputs) == 2 and model.inputs[1]["turns"][-1]["role"] == "caller"


@pytest.mark.asyncio
async def test_suspicious_requires_high_confidence_and_never_mutates_rules():
    model = FakeModel([answer("suspicious", 0.91, "finish", "我将结束本次通话。"),
                       answer("suspicious", 0.8, "finish", "请等待核实。")])
    request = SimulateRequest(turns=[{"role": "caller", "text": "请把验证码告诉我"}])
    high = await simulate(request, model)
    low = await simulate(request, model)
    assert high.action == "SIMULATED_BLOCKLIST"
    assert low.action == "SIMULATED_REVIEW"


@pytest.mark.asyncio
async def test_invalid_model_output_is_rejected_without_action():
    class Duplicate(FakeModel):
        async def generate(self, system, user):
            return '{"intent":"normal","intent":"suspicious"}', "fixture"
    request = SimulateRequest(turns=[{"role": "caller", "text": "测试"}])
    with pytest.raises(ValueError, match="Duplicate"):
        await simulate(request, Duplicate([]))


@pytest.mark.asyncio
async def test_web_page_and_api_have_explicit_simulation_boundary():
    model = FakeModel([answer("normal", 0.9, "finish", "请稍后再拨。")])
    app = create_app(model=model)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                base_url="http://127.0.0.1:8765") as client:
        page = await client.get("/")
        assert page.status_code == 200
        assert "非真实电话" in page.text and "不会接听" in page.text
        response = await client.post("/api/simulate", json={
            "turns": [{"role": "caller", "text": "我是送快递的"}]})
        assert response.status_code == 200
        assert response.json()["action"] == "SIMULATED_ALLOW_5M"
        assert response.json()["simulation_only"] is True


@pytest.mark.asyncio
async def test_input_rejects_extra_fields_and_third_caller_turn():
    app = create_app(model=FakeModel([]))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                base_url="http://127.0.0.1:8765") as client:
        assert (await client.post("/api/simulate", json={
            "turns": [{"role": "caller", "text": "你好", "phone": "123"}]})).status_code == 422
        assert (await client.post("/api/simulate", json={"turns": [
            {"role": "caller", "text": "一"},
            {"role": "assistant", "text": "问"},
            {"role": "caller", "text": "二"},
            {"role": "assistant", "text": "问"},
            {"role": "caller", "text": "三"},
        ]})).status_code == 422


@pytest.mark.asyncio
async def test_clear_normal_purpose_not_rejected_for_unverified_relationship():
    model = FakeModel([dict(intent="normal", claimed_relationship="friend",
                            confidence=0.88, next_step="finish",
                            reply="谢谢你联系我。", reason="普通叙旧")])
    result = await simulate(
        SimulateRequest(turns=[{"role": "caller", "text": "我是老朋友，想约你叙旧"}]),
        model
    )
    assert result.action == "SIMULATED_ALLOW_5M"
    assert result.claimed_relationship == "friend" and result.identity_verified is False


@pytest.mark.asyncio
async def test_commercial_claim_cannot_gain_allow_from_normal_label():
    model = FakeModel([dict(intent="normal", claimed_relationship="commercial",
                            confidence=0.99, next_step="finish",
                            reply="好的。", reason="商家自称普通事务")])
    result = await simulate(
        SimulateRequest(turns=[{"role": "caller", "text": "我是销售，介绍一下套餐"}]),
        model
    )
    assert result.action == "SIMULATED_REVIEW"



@pytest.mark.asyncio
async def test_examples_endpoint_exposes_30_synthetic_cases():
    app = create_app(model=FakeModel([]))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                base_url="http://127.0.0.1:8765") as client:
        response = await client.get("/api/examples")
        assert response.status_code == 200
        cases = response.json()
        assert len(cases) == 30
        counts = {group: sum(case["group"] == group for case in cases)
                  for group in ("sales", "colleague", "friend", "family")}
        assert counts == {"sales": 10, "colleague": 5, "friend": 5, "family": 10}
        assert all(case["opening"] and case["clarification"] for case in cases)


@pytest.mark.asyncio
async def test_clear_marketing_cannot_force_unnecessary_followup():
    model = FakeModel([dict(intent="marketing", claimed_relationship="commercial",
                            confidence=0.95, next_step="ask",
                            reply="方便介绍产品吗？", reason="明确推销")])
    result = await simulate(
        SimulateRequest(turns=[{"role": "caller", "text": "我来推销保险产品"}]),
        model
    )
    assert result.status == "FINISHED" and result.action == "SIMULATED_REVIEW"


@pytest.mark.asyncio
async def test_claimed_family_does_not_override_explicit_risk():
    model = FakeModel([dict(intent="suspicious", claimed_relationship="family",
                            confidence=0.95, next_step="finish",
                            reply="本次通话到此结束。", reason="明确索取验证码")])
    result = await simulate(
        SimulateRequest(turns=[{"role": "caller", "text": "我是家里人，请把验证码告诉我"}]),
        model
    )
    assert result.action == "SIMULATED_BLOCKLIST"
    assert result.identity_verified is False
