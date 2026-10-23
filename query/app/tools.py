"""The agent's capabilities, implemented once and exposed twice: to the LangGraph agent (in-process) and to any
MCP client (app/mcp_server.py). Every function runs as a specific user's Principals and is read-only, except
start_summary_job, which queues work the user is allowed to do."""
from datetime import date

from . import jobs, retrieval
from .permissions import Principals, late_check
from .services import Services

DOC_TYPES = {"policy", "faq", "runbook", "reference", "process", "report", "playbook", "notes"}


def parse_filters(args: dict) -> retrieval.Filters:
    """Raises ValueError for bad arguments, which callers report back as invalid_arguments."""
    doc_type = args.get("doc_type") or None
    if doc_type is not None and doc_type not in DOC_TYPES:
        raise ValueError(f"'doc_type' must be one of {sorted(DOC_TYPES)}")
    modified_after = None
    if args.get("modified_after"):
        try:
            modified_after = date.fromisoformat(str(args["modified_after"]))
        except ValueError:
            raise ValueError("'modified_after' must be YYYY-MM-DD") from None
    include = args.get("include_superseded")
    return retrieval.Filters(doc_type, modified_after, True if include is None else bool(include))


def required(args: dict, name: str, max_len: int = 500) -> str:
    v = str(args.get(name) or "").strip()
    if not v:
        raise ValueError(f"missing required argument '{name}'")
    if len(v) > max_len:
        raise ValueError(f"'{name}' is too long")
    return v


async def search_passages(svc: Services, p: Principals, args: dict) -> dict:
    """Hybrid search → live permission check → small-to-big expansion. Numbering and budgets belong to the caller."""
    query = required(args, "query")
    result = await retrieval.search(svc, p, query, parse_filters(args))
    allowed, denied = await late_check(svc, p, [h.doc_id for h in result.passages])
    kept = [h for h in result.passages if h.doc_id in allowed]
    expanded = await retrieval.expand_to_sections(svc, p.tenant_id, kept)
    unique, seen = [], set()
    for h in expanded:
        if h.section_key not in seen:
            seen.add(h.section_key)
            unique.append(h.as_source())
    return {"passages": unique, "candidates": result.candidates, "cacheHit": result.cache_hit,
            "millis": result.millis, "droppedByPermissionCheck": denied}


async def fetch_passages(svc: Services, p: Principals, doc_id: str) -> dict:
    """Whole document by section. "Missing" and "no access" look identical, so existence is never confirmed."""
    doc_id = doc_id.strip()
    allowed, _ = await late_check(svc, p, [doc_id])
    if not allowed:
        return {"found": False, "passages": []}
    sections = await retrieval.document_sections(svc, p, doc_id)
    return {"found": bool(sections), "passages": [s.as_source() for s in sections]}


async def start_summary_job(svc: Services, p: Principals, topic: str) -> dict:
    topic = topic.strip()
    if not topic or len(topic) > 300:
        raise ValueError("'topic' must be 1-300 characters")
    return await jobs.submit(svc, p.tenant_id, p.user_id, "summarize_documents", {"topic": topic})
