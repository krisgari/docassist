"""Async jobs. The query plane records the job and publishes it to Kafka; the ingestion plane's job worker runs it
(map/reduce with checkpoints) and publishes progress, which JobEventHub fans out to browsers over SSE.

Submission is idempotent: the key includes the corpus version, so the same request on an unchanged corpus reuses
the existing job, while a corpus change gets a fresh run.
"""
import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Optional

from .core.text import short_hash
from .retrieval import index_version
from .services import Services

log = logging.getLogger("docassist.jobs")

JOB_REQUESTS = "jobs.requests"
JOB_EVENTS = "jobs.events"

_COLUMNS = "job_id::text AS job_id, job_type, status, progress, progress_note, result, error, cost_usd, created_at"


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _view(r: dict, reused: bool = False, include_result: bool = True) -> dict:
    result = r.get("result") if include_result else None
    if isinstance(result, dict) and "partials" in result and "summary" not in result:
        result = None   # checkpoint data of a running job, not a result
    return {"jobId": r["job_id"], "type": r["job_type"], "status": r["status"], "progress": r["progress"],
            "note": r["progress_note"], "result": result, "error": r["error"], "costUsd": float(r["cost_usd"] or 0),
            "createdAt": r["created_at"].isoformat() if r["created_at"] else None, "reused": reused}


async def _publish(svc: Services, tenant: str, user_id: str, job_type: str, args: dict, job_id: str) -> None:
    event = {"jobId": job_id, "tenantId": tenant, "userId": user_id, "jobType": job_type, "args": args,
             "requestedAt": _now_iso()}
    await svc.producer.send_and_wait(JOB_REQUESTS, key=f"{tenant}:{job_id}".encode(), value=json.dumps(event).encode())


async def submit(svc: Services, tenant: str, user_id: str, job_type: str, args: dict) -> dict:
    version = await index_version(svc, tenant)
    key = short_hash(f"{tenant}|{user_id}|{job_type}|{json.dumps(args, sort_keys=True)}|v{version}")
    async with svc.pool.connection() as conn:
        row = await (await conn.execute(
            """INSERT INTO job (tenant_id, user_id, job_type, args, idempotency_key)
               VALUES (%s, %s, %s, %s::jsonb, %s) ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
               RETURNING job_id::text AS job_id""",
            (tenant, user_id, job_type, json.dumps(args), key))).fetchone()
        if row is None:
            existing = await (await conn.execute(
                f"SELECT {_COLUMNS} FROM job WHERE tenant_id = %s AND idempotency_key = %s", (tenant, key))).fetchone()
            if existing["status"] != "failed":
                return _view(existing, reused=True)
            # A failed job may be retried explicitly: reset it and re-queue under the same id.
            await conn.execute("UPDATE job SET status = 'queued', progress = 0, error = NULL, updated_at = now() "
                               "WHERE job_id = %s::uuid", (existing["job_id"],))
            job_id = existing["job_id"]
        else:
            job_id = row["job_id"]
    await _publish(svc, tenant, user_id, job_type, args, job_id)
    return await get(svc, tenant, user_id, job_id)


async def get(svc: Services, tenant: str, user_id: str, job_id: str) -> Optional[dict]:
    """Users can only see their own jobs."""
    try:
        uuid.UUID(job_id)
    except ValueError:
        return None
    async with svc.pool.connection() as conn:
        r = await (await conn.execute(
            f"SELECT {_COLUMNS} FROM job WHERE tenant_id = %s AND user_id = %s AND job_id = %s::uuid",
            (tenant, user_id, job_id))).fetchone()
    return _view(r) if r else None


async def recent(svc: Services, tenant: str, user_id: str) -> list[dict]:
    async with svc.pool.connection() as conn:
        rows = await (await conn.execute(
            f"SELECT {_COLUMNS} FROM job WHERE tenant_id = %s AND user_id = %s ORDER BY created_at DESC LIMIT 20",
            (tenant, user_id))).fetchall()
    return [_view(r, include_result=False) for r in rows]


class JobEventHub:
    """Consumes jobs.events with a per-instance consumer group (broadcast) and hands events to SSE subscribers."""

    def __init__(self, bootstrap: str):
        self.bootstrap = bootstrap
        self.subscribers: dict[str, set[asyncio.Queue]] = {}
        self._task: Optional[asyncio.Task] = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._run(), name="job-event-hub")

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()

    def subscribe(self, job_id: str) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue()
        self.subscribers.setdefault(job_id, set()).add(q)
        return q

    def unsubscribe(self, job_id: str, q: asyncio.Queue) -> None:
        subs = self.subscribers.get(job_id)
        if subs:
            subs.discard(q)
            if not subs:
                self.subscribers.pop(job_id, None)

    async def _run(self) -> None:
        from aiokafka import AIOKafkaConsumer
        while True:
            consumer = AIOKafkaConsumer(JOB_EVENTS, bootstrap_servers=self.bootstrap,
                                        group_id=f"query-{uuid.uuid4().hex[:12]}", auto_offset_reset="latest")
            try:
                await consumer.start()
                log.info("job event hub consuming %s", JOB_EVENTS)
                async for msg in consumer:
                    try:
                        event: dict[str, Any] = json.loads(msg.value)
                    except ValueError:
                        continue
                    for q in list(self.subscribers.get(event.get("jobId", ""), ())):
                        q.put_nowait(event)
            except asyncio.CancelledError:
                await _quietly(consumer.stop())
                raise
            except Exception as e:  # noqa: BLE001 - Kafka not ready yet, or a broker restart
                log.warning("job event hub reconnecting: %s", e)
                await _quietly(consumer.stop())
                await asyncio.sleep(3)


async def _quietly(awaitable) -> None:
    try:
        await awaitable
    except Exception:  # noqa: BLE001
        pass
