"""Hybrid retrieval over the index the ingestion plane writes.

dense (pgvector HNSW) + lexical (Postgres full text) → reciprocal rank fusion → rerank. Tenant and ACL filters run
inside both queries. Results are cached as chunk keys (never text) under the caller's ACL fingerprint and the index
version, which the ingestion plane bumps in Redis on every write.
"""
import json
import logging
import re
import time
from dataclasses import dataclass
from datetime import date, datetime, timezone
from typing import Optional

from .core.hashing_embedder import to_pgvector
from .core.ranking import Passage, rerank, rrf
from .core.text import short_hash
from .permissions import Principals
from .services import Services, redis_get, redis_set

log = logging.getLogger("docassist.retrieval")

_COLUMNS = """c.doc_id, c.ordinal, c.parent_ordinal, c.section_path, c.body, c.status, c.injection_flag,
              c.acl_principals, c.modified_at, d.title, d.url, d.source_system, d.sensitivity"""


@dataclass(frozen=True)
class Filters:
    doc_type: Optional[str] = None
    modified_after: Optional[date] = None
    include_superseded: bool = True

    def cache_part(self) -> str:
        return f"{self.doc_type}|{self.modified_after}|{self.include_superseded}"


@dataclass
class SearchResult:
    passages: list[Passage]
    candidates: int
    cache_hit: bool
    millis: int


def _row_to_passage(r: dict, fused: float = 0.0) -> Passage:
    modified = r["modified_at"]
    if modified is not None and modified.tzinfo is None:
        modified = modified.replace(tzinfo=timezone.utc)
    return Passage(r["doc_id"], r["ordinal"], r["parent_ordinal"], r["title"], r["url"], r["source_system"],
                   r["section_path"], r["body"], r["status"], r["sensitivity"], bool(r["injection_flag"]),
                   tuple(r["acl_principals"] or ()), modified, fused)


def _filter_sql(f: Filters, tenant: str, args: list) -> str:
    sql = ""
    if not f.include_superseded:
        sql += " AND status <> 'superseded'"
    if f.modified_after is not None:
        sql += " AND modified_at >= %s"
        args.append(datetime(f.modified_after.year, f.modified_after.month, f.modified_after.day, tzinfo=timezone.utc))
    if f.doc_type is not None:
        sql += " AND doc_id IN (SELECT d2.doc_id FROM document d2 WHERE d2.tenant_id = %s AND d2.doc_type = %s)"
        args.extend([tenant, f.doc_type])
    return sql


async def detect_iterative_scan(svc: Services) -> bool:
    """pgvector >= 0.8 keeps scanning the HNSW graph until enough rows pass the ACL filter (no top-k starvation)."""
    try:
        async with svc.pool.connection() as conn:
            row = await (await conn.execute("SELECT extversion FROM pg_extension WHERE extname = 'vector'")).fetchone()
        major, minor = (int(x) for x in (row["extversion"] if row else "0.0").split(".")[:2])
        return major > 0 or minor >= 8
    except Exception as e:  # noqa: BLE001
        log.warning("could not detect pgvector version: %s", e)
        return False


async def index_version(svc: Services, tenant: str) -> int:
    try:
        v = await svc.redis.get(f"idxver:{tenant}")
        return int(v) if v else 0
    except Exception:  # noqa: BLE001 - -1 disables retrieval caching for this request
        return -1


async def search(svc: Services, p: Principals, query: str, f: Filters) -> SearchResult:
    started = time.monotonic()
    s = svc.settings
    acl = p.all
    version = await index_version(svc, p.tenant_id)
    cache_key = None if version < 0 else (
        f"retr:{p.tenant_id}:{version}:{p.acl_fingerprint}:{short_hash(query.lower().strip() + '|' + f.cache_part())}")
    fused: Optional[dict[str, float]] = None
    if cache_key:
        cached = await redis_get(svc, cache_key)
        if cached:
            fused = dict(json.loads(cached))
    hit = fused is not None

    if fused is None:
        qv = await svc.embedder.embed_query(svc, query)
        dense = await _dense(svc, p.tenant_id, acl, qv, f)
        lexical = await _lexical(svc, p.tenant_id, acl, query, f)
        fused = rrf([dense, lexical])
        if cache_key:
            await redis_set(svc, cache_key, json.dumps(list(fused.items())), s.retrieval_cache_ttl)

    candidates = await _load(svc, p.tenant_id, acl, fused)
    ranked = rerank(query, candidates, s.top_k)
    return SearchResult(ranked, len(candidates), hit, int((time.monotonic() - started) * 1000))


async def _dense(svc: Services, tenant: str, acl: list[str], qv: list[float], f: Filters) -> list[str]:
    args: list = [tenant, acl]
    sql = ("SELECT doc_id || '#' || ordinal AS k FROM chunk "
           "WHERE tenant_id = %s AND status <> 'deleted' AND acl_principals && %s::text[]")
    sql += _filter_sql(f, tenant, args)
    sql += " ORDER BY embedding <=> %s::vector LIMIT %s"
    args.extend([to_pgvector(qv), svc.settings.dense_k])
    async with svc.pool.connection() as conn:
        async with conn.transaction():
            if svc.iterative_scan:
                await conn.execute("SET LOCAL hnsw.iterative_scan = relaxed_order")
            rows = await (await conn.execute(sql, args)).fetchall()
    return [r["k"] for r in rows]


async def _lexical(svc: Services, tenant: str, acl: list[str], query: str, f: Filters) -> list[str]:
    words = [w for w in dict.fromkeys(re.split(r"[\W_]+", query.lower())) if len(w) > 1]
    if not words:
        return []
    args: list = [" | ".join(words), tenant, acl]
    sql = ("SELECT c.doc_id || '#' || c.ordinal AS k FROM chunk c, to_tsquery('english', %s) q "
           "WHERE c.tenant_id = %s AND c.status <> 'deleted' AND c.acl_principals && %s::text[] AND c.tsv @@ q")
    sql += _filter_sql(f, tenant, args)
    sql += " ORDER BY ts_rank_cd(c.tsv, q) DESC LIMIT %s"
    args.append(svc.settings.lexical_k)
    async with svc.pool.connection() as conn:
        rows = await (await conn.execute(sql, args)).fetchall()
    return [r["k"] for r in rows]


async def _load(svc: Services, tenant: str, acl: list[str], fused: dict[str, float]) -> list[Passage]:
    """Loads rows for the fused keys, re-applying tenant and ACL filters (cached ids are never trusted alone)."""
    if not fused:
        return []
    async with svc.pool.connection() as conn:
        rows = await (await conn.execute(
            f"""SELECT {_COLUMNS} FROM chunk c JOIN document d ON d.tenant_id = c.tenant_id AND d.doc_id = c.doc_id
                WHERE c.tenant_id = %s AND c.status <> 'deleted' AND c.acl_principals && %s::text[]
                  AND (c.doc_id || '#' || c.ordinal) = ANY(%s)""",
            (tenant, acl, list(fused.keys())))).fetchall()
    by_key = {f'{r["doc_id"]}#{r["ordinal"]}': r for r in rows}
    return [_row_to_passage(by_key[k], score) for k, score in fused.items() if k in by_key]


async def expand_to_sections(svc: Services, tenant: str, hits: list[Passage], max_tokens_per_hit: int = 700) -> list[Passage]:
    """Small-to-big: replace each hit's body with its whole section (bounded), so the model sees full context."""
    from dataclasses import replace
    out = []
    async with svc.pool.connection() as conn:
        for h in hits:
            rows = await (await conn.execute(
                "SELECT body FROM chunk WHERE tenant_id = %s AND doc_id = %s AND parent_ordinal = %s "
                "AND status <> 'deleted' ORDER BY ordinal", (tenant, h.doc_id, h.parent_ordinal))).fetchall()
            section = "\n\n".join(r["body"] for r in rows)
            out.append(replace(h, body=section) if section.strip() and len(section) // 4 <= max_tokens_per_hit else h)
    return out


async def document_sections(svc: Services, p: Principals, doc_id: str) -> list[Passage]:
    """One document as whole sections, ACL-filtered in the query."""
    from dataclasses import replace
    async with svc.pool.connection() as conn:
        rows = await (await conn.execute(
            f"""SELECT {_COLUMNS} FROM chunk c JOIN document d ON d.tenant_id = c.tenant_id AND d.doc_id = c.doc_id
                WHERE c.tenant_id = %s AND c.doc_id = %s AND c.status <> 'deleted' AND c.acl_principals && %s::text[]
                ORDER BY c.ordinal""", (p.tenant_id, doc_id, p.all))).fetchall()
    sections: dict[int, list[Passage]] = {}
    for r in rows:
        sections.setdefault(r["parent_ordinal"], []).append(_row_to_passage(r))
    return [replace(parts[0], body="\n\n".join(x.body for x in parts).strip()) for parts in sections.values()]
