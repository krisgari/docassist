"""Query plane: FastAPI app wiring. Owns connections, the compiled LangGraph agent (with its Postgres checkpointer),
the job-event fan-out, the MCP server and tracing. The ingestion plane owns the schema (Flyway) and the index."""
import asyncio
import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from . import mcp_server, retrieval, telemetry
from .agent.graph import build_graph
from .agent.prompts import TOOLS
from .auth import router as auth_router
from .config import load_settings
from .core.extractive import ExtractiveAnswerer
from .embeddings import Embedder
from .jobs import JobEventHub
from .routes import router as api_router
from .services import Services

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("docassist.query")

settings = load_settings()
mcp_app = mcp_server.mcp.streamable_http_app(streamable_http_path="/")


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


async def _start_producer():
    from aiokafka import AIOKafkaProducer
    for _ in range(30):
        producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_bootstrap, enable_idempotence=True, acks="all")
        try:
            await producer.start()
            return producer
        except Exception as e:  # noqa: BLE001 - Kafka may still be starting
            log.warning("kafka not ready (%s), retrying", e)
            try:
                await producer.stop()
            except Exception:  # noqa: BLE001
                pass
            await asyncio.sleep(2)
    raise RuntimeError("could not connect to Kafka")


@asynccontextmanager
async def lifespan(app: FastAPI):
    import redis.asyncio as aioredis

    pool = AsyncConnectionPool(settings.database_url, min_size=2, max_size=20, open=False,
                               kwargs={"autocommit": True, "row_factory": dict_row, "prepare_threshold": None})
    await pool.open(wait=True, timeout=60)
    http = httpx.AsyncClient(timeout=30)
    svc = Services(settings=settings, pool=pool, redis=aioredis.from_url(settings.redis_url, decode_responses=True),
                   producer=await _start_producer(),
                   embedder=Embedder(settings.embeddings_provider, settings.voyage_api_key, settings.voyage_model),
                   answerer=ExtractiveAnswerer(settings.low_information_terms), http=http)
    svc.iterative_scan = await retrieval.detect_iterative_scan(svc)

    checkpointer = None
    if settings.checkpointer:
        from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
        checkpointer = AsyncPostgresSaver(pool)
        await checkpointer.setup()       # creates the checkpoint tables on first run

    primary, fallback = _models()
    app.state.svc = svc
    app.state.graph = build_graph(checkpointer)
    app.state.checkpointer = checkpointer
    app.state.primary, app.state.fallback = primary, fallback
    app.state.primary_name = "mock" if primary is None else settings.model
    app.state.fallback_name = "mock" if fallback is None else settings.fast_model
    app.state.job_hub = JobEventHub(settings.kafka_bootstrap)
    app.state.job_hub.start()
    mcp_server.bind(svc)
    log.info("query plane ready: llm=%s embeddings=%s checkpointer=%s iterative_scan=%s",
             app.state.primary_name, svc.embedder.model_id, "postgres" if checkpointer else "off", svc.iterative_scan)

    async with mcp_server.mcp.session_manager.run():
        yield

    await app.state.job_hub.stop()
    await svc.producer.stop()
    await svc.redis.aclose()
    await http.aclose()
    await pool.close()


app = FastAPI(title="docassist query plane", lifespan=lifespan)
telemetry.setup(app)
app.include_router(auth_router)
app.include_router(api_router)
app.mount("/mcp", mcp_app)


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException) -> JSONResponse:
    # Stable error shape for the UI and API clients: {"error", "message"}.
    return JSONResponse(status_code=exc.status_code, content={"error": str(exc.status_code), "message": str(exc.detail)})


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    first = exc.errors()[0] if exc.errors() else {}
    return JSONResponse(status_code=400, content={"error": "invalid_request",
                                                  "message": f"{'.'.join(map(str, first.get('loc', [])))}: {first.get('msg', 'invalid')}"})


@app.get("/health")
async def health(request: Request) -> dict:
    return {"status": "ok", "llm": request.app.state.primary_name}
