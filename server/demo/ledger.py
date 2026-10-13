"""In-memory, demo-only caller state. Never writes Android or real phone rules."""
import asyncio
import time
from datetime import datetime, timezone
from uuid import uuid4

from server.demo.logic import simulate
from server.demo.schema import SimulateRequest


class DemoError(Exception):
    def __init__(self, code: str, status: int = 409):
        self.code, self.status = code, status


class DemoLedger:
    def __init__(self, *, monotonic=None, utc_now=None):
        self._monotonic = monotonic or time.monotonic
        self._utc_now = utc_now or (lambda: datetime.now(timezone.utc))
        self._lock = asyncio.Lock()
        self._sessions = {}
        self._rules = {}
        self._events = []

    def _stamp(self):
        return self._utc_now().isoformat().replace("+00:00", "Z")

    def _record(self, session, kind, detail):
        event = {
            "at": self._stamp(), "session_id": session["id"],
            "virtual_id": session["virtual_id"], "event": kind, "detail": detail,
        }
        self._events.append(event)
        return event

    def _rule_state(self, virtual_id):
        rule = self._rules.get(virtual_id)
        if not rule:
            return "REVIEW", None
        if rule["kind"] == "BLOCK":
            return "BLOCK", None
        if self._monotonic() < rule["expires_monotonic"]:
            return "ALLOW", rule["expires_at"]
        return "EXPIRED", rule["expires_at"]

    async def start(self, virtual_id):
        async with self._lock:
            if len(self._sessions) >= 100:
                raise DemoError("DEMO_CAPACITY_REACHED", 429)
            state, _ = self._rule_state(virtual_id)
            if state == "BLOCK":
                raise DemoError("SIMULATED_CALLER_BLOCKED")
            session = {
                "id": str(uuid4()), "virtual_id": virtual_id,
                "status": "READY", "turns": [], "processing": False,
                "outcome": None,
            }
            self._sessions[session["id"]] = session
            event = self._record(session, "CALL_STARTED", "模拟来电已建立")
            return {
                "session_id": session["id"], "virtual_id": virtual_id,
                "status": "READY", "event": event, "simulation_only": True,
            }

    async def turn(self, session_id, text, model):
        async with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                raise DemoError("SESSION_NOT_FOUND", 404)
            if session["status"] not in ("READY", "CONTINUE") or session["processing"]:
                raise DemoError("SESSION_NOT_ACCEPTING_TURNS")
            turns = session["turns"] + [{"role": "caller", "text": text}]
            request = SimulateRequest.model_validate({"turns": turns})
            session["processing"] = True
            session["status"] = "PROCESSING"
        try:
            answer = await simulate(request, model)
        except BaseException:
            async with self._lock:
                session["processing"] = False
                session["status"] = "FAILED"
                self._record(session, "MODEL_FAILED", "模型结果未确认；未写入演示规则")
            raise
        async with self._lock:
            session["processing"] = False
            session["turns"] = turns + [{"role": "assistant", "text": answer.reply}]
            session["status"] = answer.status
            session["outcome"] = answer.model_dump()
            event = self._record(session, "AI_REPLIED", answer.action)
            if answer.status == "FINISHED":
                now = self._monotonic()
                if answer.action == "SIMULATED_ALLOW_5M":
                    expiry = self._utc_now().timestamp() + 300
                    expires_at = datetime.fromtimestamp(expiry, timezone.utc).isoformat().replace("+00:00", "Z")
                    self._rules[session["virtual_id"]] = {
                        "kind": "ALLOW", "expires_monotonic": now + 300,
                        "expires_at": expires_at,
                    }
                    self._record(session, "TEMP_ALLOW_SAVED", "同一虚构 ID 五分钟内模拟放行")
                elif answer.action == "SIMULATED_BLOCKLIST":
                    self._rules[session["virtual_id"]] = {"kind": "BLOCK"}
                    self._record(session, "BLOCK_SAVED", "同一虚构 ID 后续模拟拦截")
                else:
                    if self._rules.get(session["virtual_id"], {}).get("kind") == "ALLOW":
                        del self._rules[session["virtual_id"]]
                    self._record(session, "REVIEW_QUEUED", "未授予新的模拟放行；需要人工复核")
            rule_state, expires_at = self._rule_state(session["virtual_id"])
            return {
                **answer.model_dump(), "session_id": session_id,
                "virtual_id": session["virtual_id"], "rule_state": rule_state,
                "expires_at": expires_at, "event": event,
            }

    async def redial(self, virtual_id):
        async with self._lock:
            state, expires_at = self._rule_state(virtual_id)
            related = next(
                (item for item in reversed(tuple(self._sessions.values()))
                 if item["virtual_id"] == virtual_id), None
            )
            if related is None:
                raise DemoError("VIRTUAL_CALLER_NOT_FOUND", 404)
            outcome = {
                "ALLOW": "SIMULATED_RING", "BLOCK": "SIMULATED_REJECT",
                "REVIEW": "SIMULATED_REVIEW", "EXPIRED": "SIMULATED_REVIEW",
            }[state]
            event = self._record(related, "REDIAL_CHECKED", f"{state} -> {outcome}")
            return {
                "virtual_id": virtual_id, "rule_state": state,
                "result": outcome, "expires_at": expires_at,
                "event": event, "simulation_only": True,
            }

    async def timeline(self, virtual_id):
        async with self._lock:
            if not any(s["virtual_id"] == virtual_id for s in self._sessions.values()):
                raise DemoError("VIRTUAL_CALLER_NOT_FOUND", 404)
            state, expires_at = self._rule_state(virtual_id)
            return {
                "virtual_id": virtual_id, "rule_state": state,
                "expires_at": expires_at,
                "events": [event.copy() for event in self._events if event["virtual_id"] == virtual_id],
                "simulation_only": True,
            }
