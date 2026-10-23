"""The output policy every answer passes through before a user sees it, and the audit record it leaves.

Strip statements the sources don't support, drop citations that point nowhere or at documents the user can no
longer open, redact secrets and PII, build citations, classify the outcome, price the tokens, write the audit row.
"""
import json
import logging
import re
from typing import Any

from .core import costs
from .core.extractive import NOT_FOUND
from .core.guardrails import UNCITED, UNSUPPORTED, check_grounding, redact
from .core.text import sentences, sha256
from .permissions import Principals, late_check
from .services import Services

log = logging.getLogger("docassist.policy")

UNAVAILABLE = ("The assistant is temporarily unavailable, and no documents were retrieved yet. "
               "Please try again in a minute.")
_CITE = re.compile(r"\[(\d{1,2})]")


def source_texts(sources: list[dict]) -> dict[int, str]:
    return {int(s["n"]): f'{s.get("section", "")}\n{s.get("text", "")}' for s in sources if s.get("n") is not None}


def fallback_answer(svc: Services, question: str, sources: list[dict], reason: str) -> str:
    if not sources and reason == "model unavailable":
        return UNAVAILABLE
    a = svc.answerer.answer(question, sources)
    if not a.found:
        return NOT_FOUND
    return f"Here is what the documents say directly ({reason}):\n\n{a.text}"


def _outcome(text: str, citations: list[dict], used_fallback: bool, job_id) -> str:
    if text == UNAVAILABLE:
        return "error"
    if job_id:
        return "answered"
    if text.startswith(NOT_FOUND) or (not citations and "couldn't find" in text.lower()):
        return "not_found"
    return "partial" if used_fallback else "answered"


async def finalize(svc: Services, p: Principals, payload: dict[str, Any]) -> dict[str, Any]:
    guardrails = list(payload.get("guardrails") or [])
    dropped = list(payload.get("dropped") or [])
    text = payload.get("answer") or ""
    used_fallback = bool(payload.get("usedFallback"))
    sources = [s for s in (payload.get("sources") or []) if s.get("n") is not None]

    # Defense in depth: re-check every source against live permissions before anything is shown.
    allowed, denied = await late_check(svc, p, [s["docId"] for s in sources])
    if denied:
        dropped += denied
        guardrails.append({"type": "acl_late_check_drop", "detail": f"{len(denied)} document(s) removed at finalize"})
    revoked_ns = {int(s["n"]) for s in sources if s["docId"] not in allowed}
    sources = [s for s in sources if s["docId"] in allowed]
    by_n = {int(s["n"]): s for s in sources}

    # A statement drawn from a source the user can no longer open goes, not just its citation marker.
    if revoked_ns:
        removed = 0
        for sent in sentences(text):
            if any(int(m.group(1)) in revoked_ns for m in _CITE.finditer(sent)):
                text = text.replace(sent, "")
                removed += 1
        if removed:
            guardrails.append({"type": "revoked_source_statements_removed",
                               "detail": f"{removed} statement(s) relied on documents removed at finalize"})
            text = re.sub(r"[ \t]{2,}", " ", text).strip()

    if not used_fallback and sources:
        report = check_grounding(text, source_texts(sources))
        removed = 0
        for s in report.sentences:
            if s.status == UNSUPPORTED:
                text = text.replace(s.sentence, "")
                removed += 1
        if removed:
            guardrails.append({"type": "unsupported_statements_removed",
                               "detail": f"{removed} statement(s) removed by the grounding check"})
            text = re.sub(r"\n{3,}", "\n\n", re.sub(r"[ \t]+\n", "\n", text)).strip()
        uncited = sum(1 for s in report.sentences if s.status == UNCITED)
        if uncited:
            guardrails.append({"type": "uncited_statements", "detail": f"{uncited} factual statement(s) without a citation"})
        if report.invalid_citations:
            guardrails.append({"type": "invalid_citation_removed", "detail": str(report.invalid_citations)})
            for n in report.invalid_citations:
                text = text.replace(f"[{n}]", "")

    text, redacted = redact(text)
    if redacted:
        guardrails.append({"type": "sensitive_data_redacted", "detail": ", ".join(redacted)})

    citations = []
    for n in dict.fromkeys(int(m.group(1)) for m in _CITE.finditer(text)):
        s = by_n.get(n)
        if not s:
            continue
        body = s.get("text", "")
        snippet = body[:600] + "…" if len(body) > 600 else body
        citations.append({"n": n, "docId": s["docId"], "title": s.get("title"), "url": s.get("url"),
                          "section": s.get("section"), "modified": s.get("modified") or None,
                          "status": s.get("status"), "sourceSystem": s.get("sourceSystem"),
                          "snippet": redact(snippet)[0]})

    job_id = payload.get("jobId")
    outcome = _outcome(text, citations, used_fallback, job_id)

    by_model: dict[str, list[int]] = {}
    for u in payload.get("usage") or []:
        acc = by_model.setdefault(u["model"], [0, 0, 0, 0])
        for i, k in enumerate(("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens")):
            acc[i] += int(u.get(k) or 0)
    usage, total, in_tok, out_tok = [], 0.0, 0, 0
    for model, (i, o, r, w) in by_model.items():
        c = costs.cost(model, i, o, r, w)
        total += c
        in_tok += i + r + w
        out_tok += o
        usage.append({"model": model, "inputTokens": i, "outputTokens": o, "cacheReadTokens": r, "costUsd": c})

    latency = int(payload.get("latencyMs") or 0)
    trace = list(payload.get("trace") or [])
    audit_id = None
    try:
        async with svc.pool.connection() as conn:
            row = await (await conn.execute(
                """INSERT INTO audit_log (tenant_id, user_id, question, answer_hash, retrieved_doc_ids, cited_doc_ids,
                                          dropped_by_acl, guardrail_events, tool_calls, model, input_tokens,
                                          output_tokens, cost_usd, latency_ms, outcome)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, %s, %s, %s, %s, %s, %s)
                   RETURNING audit_id::text AS audit_id""",
                (p.tenant_id, p.user_id, payload.get("question", ""), sha256(text),
                 list(dict.fromkeys(s["docId"] for s in sources)),
                 list(dict.fromkeys(c["docId"] for c in citations)), list(dict.fromkeys(dropped)),
                 json.dumps(guardrails), json.dumps(trace), payload.get("model"), in_tok, out_tok, total, latency,
                 outcome))).fetchone()
            audit_id = row["audit_id"]
    except Exception:  # noqa: BLE001 - never lose the answer because the audit insert failed; alert on this log
        log.exception("audit write failed")

    return {"answer": text, "outcome": outcome, "citations": citations, "trace": trace, "guardrails": guardrails,
            "droppedByPermissionCheck": list(dict.fromkeys(dropped)),
            "passagesConsidered": int(payload.get("passagesConsidered") or 0), "usage": usage, "costUsd": total,
            "latencyMs": latency, "auditId": audit_id, "jobId": job_id}
