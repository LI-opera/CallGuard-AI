"""Provider factory. Model credentials stay on the server, never in Android."""
import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit
import httpx

@dataclass(frozen=True)
class ModelConfig:
    provider: str
    model: str
    base_url: str
    api_key: str = field(repr=False)
    timeout: float = 20

    def __post_init__(self):
        url=urlsplit(self.base_url)
        if self.provider not in {"deepseek","openai_compatible"}:raise ValueError("Unsupported provider")
        if url.scheme!="https" or not url.hostname or url.username or url.query or url.fragment:raise ValueError("HTTPS endpoint required")
        if self.provider=="deepseek" and url.hostname!="api.deepseek.com":raise ValueError("DeepSeek endpoint mismatch")
        if not self.api_key or not self.model or not 1<=self.timeout<=60:raise ValueError("Invalid model configuration")

    @classmethod
    def from_env(cls):
        provider=os.environ.get("CALLGUARD_AI_PROVIDER","deepseek")
        prefix={"deepseek":"DEEPSEEK","openai_compatible":"MODEL"}.get(provider)
        if prefix is None:raise ValueError("Unsupported model provider")
        base=os.environ.get(prefix+"_BASE_URL","https://api.deepseek.com" if provider=="deepseek" else "").rstrip("/")
        model=os.environ.get(prefix+"_MODEL","deepseek-chat" if provider=="deepseek" else "")
        key=os.environ.get(prefix+"_API_KEY","")
        url=urlsplit(base)
        if url.scheme!="https" or not url.hostname or url.username or url.query or url.fragment:
            raise ValueError("Model endpoint must be credential-free HTTPS")
        if provider=="deepseek" and url.hostname!="api.deepseek.com":raise ValueError("DeepSeek endpoint mismatch")
        timeout=float(os.environ.get("CALLGUARD_AI_TIMEOUT_SECONDS","20"))
        if not key or not model or not 1<=timeout<=60:raise ValueError("Incomplete model configuration")
        return cls(provider,model,base,key,timeout)

class ChatCompletionModel:
    def __init__(self,config,transport=None):self.config,self.transport=config,transport
    async def generate(self,system,user):
        payload={"model":self.config.model,"messages":[{"role":"system","content":system},{"role":"user","content":user}],
                 "response_format":{"type":"json_object"},"max_tokens":384,"temperature":0}
        if self.config.provider=="deepseek":payload["thinking"]={"type":"disabled"}
        # No retry: uncertain provider outcomes must not silently duplicate paid calls.
        async with httpx.AsyncClient(timeout=self.config.timeout,transport=self.transport,follow_redirects=False) as client:
            async with client.stream("POST",self.config.base_url+"/chat/completions",json=payload,
                                     headers={"Authorization":"Bearer "+self.config.api_key}) as response:
                response.raise_for_status()
                raw=bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw)>65536:raise ValueError("Model response too large")
                import json
                data=json.loads(raw)
        choice=data["choices"][0]
        if choice.get("finish_reason")!="stop":raise ValueError("Incomplete model output")
        content=choice["message"]["content"]
        if not isinstance(content,str) or len(content)>4096:raise ValueError("Invalid model content")
        return content,str(data.get("model",self.config.model))

def create_model(config=None,transport=None):
    config=config or ModelConfig.from_env()
    if config.provider not in {"deepseek","openai_compatible"}:raise ValueError("Unsupported provider")
    return ChatCompletionModel(config,transport)
