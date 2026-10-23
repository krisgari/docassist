"""HTTP API used by the UI and the eval suite. Same contract the earlier Java query-api exposed."""
import asyncio
import json
import logging
import time
import uuid
from typing import Any, AsyncIterator, Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from . import jobs, ratelimit
from .agent import graph as agent_graph
from .agent.backend import LocalBackend
from .auth import CurrentUser, current_user
from .permissions import principals_for
from .telemetry import set_attributes, span

log = logging.getLogger("docassist.routes")
router = APIRouter()
SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


def sse(event: str, data: Any) -> str:
    return f"event: {event}\ndata: {json.dumps(jsonable_encoder(data), separators=(',', ':'))}\n\n"


# ------------------------------------------------------------------------------------------------ chat

class TurnIn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = ""


class ChatIn(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    history: list[TurnIn] = []
    conversationId: Optional[str] = Field(default=None, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")


@router.post("/api/chat")
async def chat(body: ChatIn, request: Request, user: CurrentUser = Depends(current_user)) -> StreamingResponse:
    svc = request.app.state.svc
    if not await ratelimit.allow(svc, user.tenant_id, user.user_id):
        raise HTTPException(status_code=429, detail="Too many questions in the last minute.")
    return StreamingResponse(_run_turn(request, body, user), media_type="text/event-stream", headers=SSE_HEADERS)


async def _run_turn(request: Request, body: ChatIn, user: CurrentUser) -> AsyncIterator[str]:
    app = request.app
    svc = app.state.svc
    question = body.question.strip()
    with span("chat.turn", **{"user.id": user.user_id, "conversation.id": body.conversationId}) as s:
        try:
            principals = await principals_for(svc, user.tenant_id, user.user_id)
            deps = agent_graph.Deps(limits=svc.settings.limits, backend=LocalBackend(svc, principals),
                                    primary=app.state.primary, fallback=app.state.fallback,
                                    primary_name=app.state.primary_name, fallback_name=app.state.fallback_name)
            # The thread id is derived from the verified identity, so nobody can read another user's conversation.
            thread = f"{user.tenant_id}:{user.user_id}:{body.conversationId or uuid.uuid4().hex}"
            config = {"configurable": {"thread_id": thread}, "recursion_limit": 40}
            continuing = False
            if body.conversationId and app.state.checkpointer is not None:
                snapshot = await app.state.graph.aget_state(config)
                continuing = bool((snapshot.values or {}).get("messages"))
            inputs = agent_graph.turn_input(question, [] if continuing else [t.model_dump() for t in body.history],
                                            svc.settings.limits.history_turns)
            token = agent_graph.DEPS.set(deps)   # run-scoped dependencies stay out of checkpoints
            final: Optional[dict] = None
            try:
                async for mode, chunk in app.state.graph.astream(inputs, config, stream_mode=["custom", "values"]):
                    if mode == "custom":
                        yield sse(chunk["event"], chunk["data"])
                    elif mode == "values":
                        final = chunk
            finally:
                agent_graph.DEPS.reset(token)
            result = (final or {}).get("result")
            if result:
                set_attributes(s, **{"answer.outcome": result.get("outcome"), "answer.cost_usd": result.get("costUsd")})
                yield sse("answer", result)
            else:
                yield sse("error", {"message": "The assistant couldn't finish this answer. Please try again."})
        except Exception:  # noqa: BLE001 - one failed turn must still end the stream with a message
            log.exception("chat turn failed")
            yield sse("error", {"message": "Something went wrong answering that. Please try again."})


# ------------------------------------------------------------------------------------------------ jobs

class SummarizeIn(BaseModel):
    topic: str = Field(min_length=1, max_length=300)


@router.post("/api/jobs/summarize")
async def summarize(body: SummarizeIn, request: Request, user: CurrentUser = Depends(current_user)) -> dict:
    return await jobs.submit(request.app.state.svc, user.tenant_id, user.user_id, "summarize_documents",
                             {"topic": body.topic.strip()})


@router.get("/api/jobs")
async def recent_jobs(request: Request, user: CurrentUser = Depends(current_user)) -> list[dict]:
    return await jobs.recent(request.app.state.svc, user.tenant_id, user.user_id)


@router.get("/api/jobs/{job_id}")
async def get_job(job_id: str, request: Request, user: CurrentUser = Depends(current_user)) -> dict:
    view = await jobs.get(request.app.state.svc, user.tenant_id, user.user_id, job_id)
    if view is None:
        raise HTTPException(status_code=404, detail="no such job")
    return view


@router.get("/api/jobs/{job_id}/events")
async def job_events(job_id: str, request: Request, user: CurrentUser = Depends(current_user)) -> StreamingResponse:
    svc, hub = request.app.state.svc, request.app.state.job_hub
    view = await jobs.get(svc, user.tenant_id, user.user_id, job_id)
    if view is None:
        raise HTTPException(status_code=404, detail="no such job")

    async def stream() -> AsyncIterator[str]:
        yield sse("snapshot", view)          # current state first, so a late subscriber is never stuck
        if view["status"] in ("succeeded", "failed"):
            return
        q = hub.subscribe(job_id)
        deadline = time.monotonic() + 15 * 60
        try:
            while time.monotonic() < deadline:
                try:
                    event = await asyncio.wait_for(q.get(), timeout=15)
                except asyncio.TimeoutError:
                    # Re-poll in case an event was missed (e.g. this instance restarted), and keep the connection alive.
                    latest = await jobs.get(svc, user.tenant_id, user.user_id, job_id)
                    if latest and latest["status"] in ("succeeded", "failed"):
                        yield sse("progress", {"jobId": job_id, "status": latest["status"],
                                               "progress": latest["progress"], "note": latest["note"]})
                        return
                    yield ": keepalive\n\n"
                    continue
                yield sse("progress", event)
                if event.get("status") in ("succeeded", "failed"):
                    return
        finally:
            hub.unsubscribe(job_id, q)

    return StreamingResponse(stream(), media_type="text/event-stream", headers=SSE_HEADERS)


# ------------------------------------------------------------------------------------------------ feedback

class FeedbackIn(BaseModel):
    auditId: str
    rating: Literal[1, -1]
    reason: Optional[Literal["wrong", "outdated", "missing_source", "cant_open_source", "other"]] = None
    comment: Optional[str] = Field(default=None, max_length=1000)


@router.post("/api/feedback")
async def feedback(body: FeedbackIn, request: Request, user: CurrentUser = Depends(current_user)) -> dict:
    """Thumbs up / down with a reason; negative feedback is triaged from the audit trace into the golden set."""
    svc = request.app.state.svc
    try:
        uuid.UUID(body.auditId)
    except ValueError:
        raise HTTPException(status_code=400, detail="invalid audit id") from None
    async with svc.pool.connection() as conn:
        owned = await (await conn.execute(
            "SELECT 1 FROM audit_log WHERE audit_id = %s::uuid AND tenant_id = %s AND user_id = %s",
            (body.auditId, user.tenant_id, user.user_id))).fetchone()
        if owned is None:
            raise HTTPException(status_code=404, detail="no such answer")
        await conn.execute(
            "INSERT INTO feedback (tenant_id, audit_id, user_id, rating, reason, comment) VALUES (%s, %s::uuid, %s, %s, %s, %s)",
            (user.tenant_id, body.auditId, user.user_id, body.rating, body.reason, body.comment))
    return {"ok": True}


# ------------------------------------------------------------------------------------------------ operations

@router.get("/api/admin/insights")
async def insights(request: Request, user: CurrentUser = Depends(current_user)) -> dict:
    """Quality, cost, latency, freshness and recent traces. Execs only in the demo (an admin group in production)."""
    svc = request.app.state.svc
    p = await principals_for(svc, user.tenant_id, user.user_id)
    if "execs" not in p.groups:
        raise HTTPException(status_code=403, detail="admins only")
    t = user.tenant_id
    async with svc.pool.connection() as conn:
        async def one(sql: str) -> dict:
            return await (await conn.execute(sql, (t,))).fetchone()

        async def many(sql: str) -> list[dict]:
            return await (await conn.execute(sql, (t,))).fetchall()

        out = {
            "totals": await one("""
                SELECT count(*) AS questions,
                       coalesce(round(avg(cost_usd)::numeric, 5), 0) AS avg_cost_usd,
                       coalesce(sum(cost_usd), 0) AS total_cost_usd,
                       coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms), 0) AS p50_latency_ms,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95_latency_ms,
                       count(*) FILTER (WHERE outcome = 'not_found') AS not_found,
                       count(*) FILTER (WHERE outcome = 'partial') AS partial,
                       count(*) FILTER (WHERE outcome = 'error') AS errors,
                       count(*) FILTER (WHERE cardinality(dropped_by_acl) > 0) AS acl_drift_events
                FROM audit_log WHERE tenant_id = %s AND created_at > now() - interval '7 days'"""),
            "feedback": await one("""
                SELECT count(*) FILTER (WHERE rating = 1) AS up, count(*) FILTER (WHERE rating = -1) AS down
                FROM feedback WHERE tenant_id = %s"""),
            "guardrails": await many("""
                SELECT e->>'type' AS type, count(*) AS n FROM audit_log, jsonb_array_elements(guardrail_events) e
                WHERE tenant_id = %s GROUP BY 1 ORDER BY 2 DESC"""),
            "index": await many("""
                SELECT d.doc_id, d.title, d.status, d.sensitivity, d.modified_at, d.indexed_at,
                       cardinality(d.acl_principals) AS acl_entries, count(c.ordinal) AS chunks,
                       bool_or(coalesce(c.injection_flag, false)) AS injection_flag
                FROM document d LEFT JOIN chunk c ON c.tenant_id = d.tenant_id AND c.doc_id = d.doc_id
                WHERE d.tenant_id = %s GROUP BY d.tenant_id, d.doc_id ORDER BY d.indexed_at DESC"""),
            "recent": await many("""
                SELECT a.audit_id, a.user_id, a.question, a.outcome, a.latency_ms, a.cost_usd, a.cited_doc_ids,
                       a.dropped_by_acl, jsonb_array_length(a.tool_calls) AS tool_calls, a.guardrail_events,
                       a.created_at, f.rating, f.reason
                FROM audit_log a LEFT JOIN feedback f ON f.audit_id = a.audit_id
                WHERE a.tenant_id = %s ORDER BY a.created_at DESC LIMIT 25"""),
        }
    return jsonable_encoder(out)
