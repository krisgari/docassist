"""HTTP entry point: POST /v1/chat streams the graph's progress and final answer as Server-Sent Events.

Only the docassist API gateway calls this service (service token required). The gateway has already authenticated
the user; their JWT is forwarded on every call back into the API, where permissions are enforced.
"""
import hmac
import json
import logging
from typing import Any, AsyncIterator, Literal

import httpx
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .backend import Backend
from .config import load_settings
from .graph import Deps, build_graph, initial_state
from .prompts import TOOLS

log = logging.getLogger("docassist.agent")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

settings = load_settings()
graph = build_graph()
app = FastAPI(title="docassist agent (LangGraph)")
_http = httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=5.0))


def _models():
    """Primary and failover chat models with tools bound; (None, None) in mock mode."""
    if settings.use_mock:
        return None, None
    from langchain_anthropic import ChatAnthropic

    common = dict(api_key=settings.anthropic_api_key, max_tokens=settings.max_tokens, timeout=60, max_retries=2)
    primary = ChatAnthropic(model=settings.model, **common).bind_tools(TOOLS)
    fallback = None
    if settings.fast_model and settings.fast_model != settings.model:
        fallback = ChatAnthropic(model=settings.fast_model, **common).bind_tools(TOOLS)
    return primary, fallback


PRIMARY, FALLBACK = _models()
log.info("agent ready: mode=%s model=%s fallback=%s", "mock" if PRIMARY is None else "anthropic",
         settings.model, settings.fast_model)


class Turn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = ""


class ChatIn(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    history: list[Turn] = []


@app.get("/health")
async def health() -> dict[str, Any]:
    return {"status": "ok", "mode": "mock" if PRIMARY is None else "anthropic", "model": settings.model}


def _sse(event: str, data: Any) -> str:
    return f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"


@app.post("/v1/chat")
async def chat(body: ChatIn, authorization: str | None = Header(default=None),
               x_internal_token: str | None = Header(default=None)) -> StreamingResponse:
    if not x_internal_token or not hmac.compare_digest(x_internal_token, settings.internal_token):
        raise HTTPException(status_code=403, detail="service token required")
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="user token required")
    user_token = authorization.removeprefix("Bearer ").strip()
    return StreamingResponse(_run(body, user_token), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


async def _run(body: ChatIn, user_token: str) -> AsyncIterator[str]:
    deps = Deps(settings=settings, backend=Backend(_http, settings.api_url, settings.internal_token, user_token),
                primary=PRIMARY, fallback=FALLBACK,
                primary_name="mock" if PRIMARY is None else settings.model,
                fallback_name="mock" if FALLBACK is None else settings.fast_model)
    inputs = initial_state(body.question.strip(), [t.model_dump() for t in body.history], settings.history_turns)
    config = {"configurable": {"deps": deps}, "recursion_limit": 40}
    final: dict[str, Any] | None = None
    try:
        async for mode, chunk in graph.astream(inputs, config, stream_mode=["custom", "values"]):
            if mode == "custom":
                yield _sse(chunk["event"], chunk["data"])
            elif mode == "values":
                final = chunk
        if final and final.get("result"):
            yield _sse("answer", final["result"])
        else:
            yield _sse("error", {"message": "The assistant couldn't finish this answer. Please try again."})
    except Exception:  # noqa: BLE001 - one bad turn must not take the stream down without telling the user
        log.exception("agent turn failed")
        yield _sse("error", {"message": "Something went wrong answering that. Please try again."})
