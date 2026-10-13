"""Standalone localhost web app for simulated caller-text conversations."""
import json
from pathlib import Path
from fastapi import FastAPI, HTTPException, Path as PathParam, Request
from fastapi.responses import FileResponse
from server.demo.logic import simulate
from server.demo.ledger import DemoError, DemoLedger
from server.demo.schema import (
    DemoTurnRequest, RedialRequest, SimulateRequest, StartDemoRequest,
)
from server.app.models.factory import create_model

PAGE = Path(__file__).parent / "index.html"
EXAMPLES = Path(__file__).parent / "evaluation" / "cases.json"
ORIGINS = {"http://127.0.0.1:8765", "http://localhost:8765"}


def _local_request(request: Request):
    origin = request.headers.get("origin")
    if origin and origin not in ORIGINS:
        raise HTTPException(status_code=403, detail="ORIGIN_REJECTED")
    if request.headers.get("content-type", "").split(";", 1)[0] != "application/json":
        raise HTTPException(status_code=415, detail="JSON_REQUIRED")


def _model(app):
    try:
        return app.state.model or create_model()
    except (ValueError, KeyError):
        raise HTTPException(status_code=503, detail="MODEL_NOT_CONFIGURED") from None


def _failed(error: Exception):
    if isinstance(error, DemoError):
        raise HTTPException(status_code=error.status, detail=error.code) from None
    if isinstance(error, (ValueError, TypeError, KeyError)):
        raise HTTPException(status_code=502, detail="INVALID_MODEL_OUTPUT") from None
    raise HTTPException(status_code=503, detail="MODEL_UNAVAILABLE") from None


def create_app(model=None, ledger=None):
    app = FastAPI(title="CallGuard AI 文字模拟", version="0.2")
    app.state.model = model
    app.state.ledger = ledger or DemoLedger()

    @app.get("/")
    async def home():
        return FileResponse(PAGE, headers={"Cache-Control": "no-store",
                                           "X-Content-Type-Options": "nosniff"})

    @app.get("/api/examples")
    async def examples():
        return json.loads(EXAMPLES.read_text(encoding="utf-8-sig"))

    @app.post("/api/simulate")
    async def respond(body: SimulateRequest, request: Request):
        """Legacy stateless text evaluation endpoint; does not save demo rules."""
        _local_request(request)
        try:
            return await simulate(body, _model(app))
        except Exception as error:
            _failed(error)

    @app.post("/api/demo/start")
    async def start(body: StartDemoRequest, request: Request):
        _local_request(request)
        try:
            return await app.state.ledger.start(body.virtual_id)
        except DemoError as error:
            _failed(error)

    @app.post("/api/demo/{session_id}/turn")
    async def turn(session_id: str, body: DemoTurnRequest, request: Request):
        _local_request(request)
        try:
            return await app.state.ledger.turn(session_id, body.text, _model(app))
        except Exception as error:
            _failed(error)

    @app.post("/api/demo/redial")
    async def redial(body: RedialRequest, request: Request):
        _local_request(request)
        try:
            return await app.state.ledger.redial(body.virtual_id)
        except DemoError as error:
            _failed(error)

    @app.get("/api/demo/{virtual_id}/timeline")
    async def timeline(virtual_id: str = PathParam(pattern=r"^demo-[a-z0-9-]{3,32}$", max_length=37)):
        try:
            return await app.state.ledger.timeline(virtual_id)
        except DemoError as error:
            _failed(error)

    return app
