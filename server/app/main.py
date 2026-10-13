import os
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from server.app.api.risk import router
from server.app.domain.errors import RiskError
from server.app.domain.risk_service import RiskService
from server.app.storage import InfrastructureStore

def create_app(service=None, api_key=None, ai_service=None):
    key=api_key or os.environ.get("CALLGUARD_API_KEY","")
    if len(key)<32:
        raise RuntimeError("Configure CALLGUARD_API_KEY (at least 32 characters)")
    @asynccontextmanager
    async def lifespan(app):
        if service is None:
            store=InfrastructureStore(os.environ["CALLGUARD_REDIS_URL"],os.environ["CALLGUARD_POSTGRES_DSN"])
            await store.initialize()
            app.state.service=RiskService(store)
            ai_store=None
            if os.environ.get("CALLGUARD_AI_ENABLED","false").lower()=="true":
                from server.app.models.factory import create_model
                from server.app.ai.storage import ExplanationStore
                from server.app.ai.service import ExplanationService
                ai_store=ExplanationStore(os.environ["CALLGUARD_REDIS_URL"],os.environ["CALLGUARD_POSTGRES_DSN"],limit=6,prefix="callguard-ai")
                await ai_store.initialize()
                app.state.ai_service=ExplanationService(ai_store,create_model())
            try:
                yield
            finally:
                await store.close()
                if ai_store is not None:await ai_store.close()
        else:
            app.state.service=service
            yield
    app=FastAPI(title="CallGuard Risk API",version="0.8.0",lifespan=lifespan)
    app.state.api_key=key
    app.state.ai_service=ai_service
    if service is not None:
        app.state.service=service
    @app.exception_handler(RiskError)
    async def known_error(request:Request,error:RiskError):
        return JSONResponse({"error_code":error.code},status_code=error.status)
    @app.exception_handler(Exception)
    async def unknown_error(request:Request,error:Exception):
        return JSONResponse({"error_code":"DEPENDENCY_UNAVAILABLE"},status_code=503)
    app.include_router(router)
    from server.app.api.explanations import router as explanations_router
    app.include_router(explanations_router)
    from fastapi.openapi.utils import get_openapi
    from server.app.schemas.risk import RiskRequest, RiskResponse
    from server.app.ai.schema import ExplainRequest, ModelExplanation
    def contract_openapi():
        if app.openapi_schema:
            return app.openapi_schema
        schema=get_openapi(title=app.title,version=app.version,routes=app.routes)
        components=schema.setdefault("components",{}).setdefault("schemas",{})
        for model in (RiskRequest,RiskResponse,ExplainRequest,ModelExplanation):
            definition=model.model_json_schema(ref_template="#/components/schemas/{model}")
            components.update(definition.pop("$defs",{}))
            components[model.__name__]=definition
        operation=schema["paths"]["/v1/risk/analyze"]["post"]
        operation["requestBody"]={"required":True,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/RiskRequest"}}}}
        operation["responses"]["200"]={"description":"Validated finite adjustment; no final call action","content":{"application/json":{"schema":{"$ref":"#/components/schemas/RiskResponse"}}}}
        schema["components"]["securitySchemes"]={"BearerKey":{"type":"http","scheme":"bearer"}}
        operation["security"]=[{"BearerKey":[]}]
        ai_operation=schema["paths"]["/v1/explanations"]["post"]
        ai_operation["security"]=[{"BearerKey":[]}]
        ai_operation["requestBody"]={"required":True,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/ExplainRequest"}}}}
        app.openapi_schema=schema
        return schema
    app.openapi=contract_openapi
    @app.get("/health/live")
    async def live():
        return {"status":"alive","risk_provider":"neutral-no-model"}
    return app
