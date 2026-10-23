"""Output policy and tool-argument tests with an in-memory stand-in for the database."""
import asyncio
from contextlib import asynccontextmanager

import pytest

from app import policy, tools
from app.agent.backend import LocalBackend
from app.config import AgentLimits, Settings
from app.core.extractive import NOT_FOUND, ExtractiveAnswerer
from app.permissions import Principals
from app.services import Services

ACL = {"hr-parental-leave-2026": ["group:employees"], "hr-comp-bands-2026": ["group:hr"]}


class FakeCursor:
    def __init__(self, rows):
        self.rows = rows

    async def fetchall(self):
        return self.rows

    async def fetchone(self):
        return self.rows[0] if self.rows else None


class FakeConn:
    def __init__(self, log):
        self.log = log

    async def execute(self, sql, params=()):
        self.log.append((sql, params))
        if "FROM document WHERE" in sql:
            return FakeCursor([{"doc_id": d, "acl_principals": ACL[d], "status": "active"} for d in params[1] if d in ACL])
        if "INSERT INTO audit_log" in sql:
            return FakeCursor([{"audit_id": "00000000-0000-0000-0000-000000000001"}])
        return FakeCursor([])


class FakePool:
    def __init__(self):
        self.log = []

    @asynccontextmanager
    async def connection(self):
        yield FakeConn(self.log)


def settings() -> Settings:
    return Settings(database_url="", redis_url="", kafka_bootstrap="", tenant="acme", jwt_secret="x" * 32,
                    demo_login_enabled=True, token_ttl_minutes=60, anthropic_api_key="", llm_provider="mock",
                    model="claude-sonnet-5-5", fast_model="claude-haiku-5-5", max_tokens=1024,
                    embeddings_provider="local", voyage_api_key="", voyage_model="", dense_k=50, lexical_k=50, top_k=8,
                    retrieval_cache_ttl=60, group_cache_ttl=60, chat_per_minute=30, checkpointer=False,
                    low_information_terms=("halcyon",), limits=AgentLimits())


def svc() -> Services:
    return Services(settings=settings(), pool=FakePool(), redis=None, producer=None, embedder=None,
                    answerer=ExtractiveAnswerer(("halcyon",)))


ALICE = Principals("acme", "alice", ("employees", "engineering"))
LEAVE = {"n": 1, "docId": "hr-parental-leave-2026", "parentOrdinal": 2, "title": "Parental Leave Policy (2026)",
         "url": "https://x/leave", "sourceSystem": "sharepoint", "section": "Leave entitlement", "modified": "2026-03-02",
         "status": "active", "flagged": False,
         "text": "Primary caregivers receive 20 weeks of fully paid leave. Secondary caregivers receive 10 weeks of fully paid leave."}
COMP = {**LEAVE, "n": 2, "docId": "hr-comp-bands-2026", "title": "Compensation Bands", "text": "Staff Engineer: 215,000 - 260,000."}


def finalize(answer, sources, **extra):
    s = svc()
    payload = {"question": "q", "answer": answer, "usedFallback": False, "sources": sources, "trace": [],
               "guardrails": [], "dropped": [], "usage": [], "passagesConsidered": 3, "latencyMs": 1200,
               "jobId": None, "model": "claude-sonnet-5-5", **extra}
    return asyncio.run(policy.finalize(s, ALICE, payload)), s


def test_supported_answer_passes_with_citations_and_audit():
    result, s = finalize("Primary caregivers receive 20 weeks of fully paid leave [1].", [LEAVE],
                         usage=[{"model": "claude-sonnet-5-5", "inputTokens": 9000, "outputTokens": 700,
                                 "cacheReadTokens": 7000, "cacheWriteTokens": 0}])
    assert result["outcome"] == "answered"
    assert [c["docId"] for c in result["citations"]] == ["hr-parental-leave-2026"]
    assert result["auditId"].endswith("1")
    assert abs(result["costUsd"] - (9000 * 2e-6 + 7000 * 2e-6 * 0.05 + 700 * 1e-5)) < 1e-12
    assert any("INSERT INTO audit_log" in sql for sql, _ in s.pool.log)


def test_unsupported_sentence_is_removed():
    result, _ = finalize("Primary caregivers receive 20 weeks of fully paid leave [1]. They also get 52 weeks of sabbatical [1].", [LEAVE])
    assert "52 weeks" not in result["answer"]
    assert any(g["type"] == "unsupported_statements_removed" for g in result["guardrails"])


def test_sources_the_user_can_no_longer_open_are_dropped_and_uncited():
    result, _ = finalize("Primary caregivers receive 20 weeks of fully paid leave [1]. "
                         "A Staff Engineer earns 215,000 - 260,000 [2].", [LEAVE, COMP])
    assert "hr-comp-bands-2026" in result["droppedByPermissionCheck"]
    assert "215,000" not in result["answer"] and "[2]" not in result["answer"]
    assert [c["n"] for c in result["citations"]] == [1]
    assert any(g["type"] == "revoked_source_statements_removed" for g in result["guardrails"])


def test_numbers_missing_from_the_source_are_removed():
    result, _ = finalize("The 2026 policy gives 26 weeks to primary caregivers [1].", [LEAVE])
    assert "26 weeks" not in result["answer"]


def test_secrets_are_redacted_from_answers_and_snippets():
    leaky = {**LEAVE, "text": LEAVE["text"] + " Old key AKIAIOSFODNN7EXAMPLE."}
    result, _ = finalize("The old key was AKIAIOSFODNN7EXAMPLE [1].", [leaky], usedFallback=True)
    assert "AKIA" not in result["answer"] and "AKIA" not in result["citations"][0]["snippet"]
    assert any(g["type"] == "sensitive_data_redacted" for g in result["guardrails"])


def test_outcomes():
    assert finalize(NOT_FOUND, [])[0]["outcome"] == "not_found"
    assert finalize(policy.UNAVAILABLE, [])[0]["outcome"] == "error"
    assert finalize("Started your summary.", [], jobId="j1")[0]["outcome"] == "answered"
    assert finalize("Primary caregivers receive 20 weeks of fully paid leave [1].", [LEAVE], usedFallback=True)[0]["outcome"] == "partial"


def test_fallback_answers():
    s = svc()
    backend = LocalBackend(s, ALICE)
    assert asyncio.run(backend.extractive("q", [], "model unavailable"))["text"] == policy.UNAVAILABLE
    r = asyncio.run(backend.extractive("How many weeks of paid leave for a primary caregiver?", [LEAVE], "time budget reached"))
    assert r["found"] and r["text"].startswith("Here is what the documents say directly (time budget reached)")
    v = asyncio.run(backend.verify("Everyone gets 52 weeks [1].", [LEAVE]))
    assert v["failureRate"] == 1.0 and "52 weeks" in v["revisionPrompt"]


def test_tool_argument_validation():
    with pytest.raises(ValueError, match="YYYY-MM-DD"):
        tools.parse_filters({"modified_after": "last week"})
    with pytest.raises(ValueError, match="doc_type"):
        tools.parse_filters({"doc_type": "spreadsheet"})
    with pytest.raises(ValueError, match="query"):
        tools.required({"query": "  "}, "query")
    f = tools.parse_filters({"doc_type": "policy", "modified_after": "2026-01-01", "include_superseded": False})
    assert (f.doc_type, str(f.modified_after), f.include_superseded) == ("policy", "2026-01-01", False)
