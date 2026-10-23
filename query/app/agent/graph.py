"""The docassist agent as a LangGraph state machine.

    START → prepare → agent ─┬─ tool calls ──→ tools ──→ agent          (bounded by call, tool and time budgets)
                             ├─ final draft ─→ verify ─┬─ supported ──────→ finalize → END
                             │                         ├─ unsupported, 1st → revise → agent
                             │                         └─ unsupported, 2nd → fallback → finalize
                             └─ budget hit / model down ────────────────→ fallback → finalize

With the Postgres checkpointer each conversation is a thread: `prepare` compacts the previous turns (drops tool
calls, tool results, drafts and revision prompts, keeps questions and final answers, bounded to the last N
exchanges) so context stays small and old source numbers never leak into a new turn.

The graph decides what happens next. What is allowed, and what the user finally sees, is decided by the backend it
is given (app/agent/backend.py): permission-checked retrieval, the grounding check, redaction, citations, cost and
the audit record.
"""
import contextvars
import json
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import date
from typing import Any, Optional

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, RemoveMessage, SystemMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph

from ..config import AgentLimits
from ..core.sources import format_sources
from ..telemetry import traced_node
from .errors import BackendError, is_retryable_model_error
from .prompts import SYSTEM, user_turn
from .state import PER_TURN_DEFAULTS, AgentState

NOT_FOUND_HINT = "No passages matched. Try different terms, or tell the user nothing was found."
BUDGET_MESSAGE = "Tool budget for this question is used up. Answer with the sources you have."
FINAL, REVISION = "final", "revision"


@dataclass
class Deps:
    """Per-request dependencies, passed in config["configurable"]["deps"] (never stored in state or checkpoints)."""
    limits: AgentLimits
    backend: Any                      # app.agent.backend.LocalBackend or a test double
    primary: Any = None               # chat model with tools bound (None in mock mode)
    fallback: Any = None
    primary_name: str = "mock"
    fallback_name: str = "mock"
    started: float = field(default_factory=time.monotonic)

    @property
    def mock(self) -> bool:
        return self.primary is None

    def elapsed(self) -> float:
        return time.monotonic() - self.started


# Run-scoped dependencies. The service sets this context variable for the duration of one turn (it is inherited by
# the tasks LangGraph runs nodes in), so live clients never end up in config that a checkpointer might persist.
# Tests may pass them as config["configurable"]["deps"] instead.
DEPS: contextvars.ContextVar[Deps] = contextvars.ContextVar("docassist_agent_deps")


def _deps(config: RunnableConfig) -> Deps:
    deps = (config.get("configurable") or {}).get("deps")
    return deps if deps is not None else DEPS.get()


def _emit(event: str, data: dict[str, Any]) -> None:
    get_stream_writer()({"event": event, "data": data})


def text_of(message: AnyMessage) -> str:
    content = message.content
    if isinstance(content, str):
        return content.strip()
    parts = [b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"]
    return "".join(parts).strip()


def usage_of(message: AIMessage, model_name: str) -> dict[str, Any]:
    """Provider usage as uncached input / output / cache read / cache write, for cost accounting."""
    meta = message.response_metadata or {}
    model = meta.get("model") or meta.get("model_name") or model_name
    raw = meta.get("usage") or {}
    if isinstance(raw, dict) and "input_tokens" in raw:
        return {"model": model, "inputTokens": int(raw.get("input_tokens") or 0),
                "outputTokens": int(raw.get("output_tokens") or 0),
                "cacheReadTokens": int(raw.get("cache_read_input_tokens") or 0),
                "cacheWriteTokens": int(raw.get("cache_creation_input_tokens") or 0)}
    um = message.usage_metadata or {}
    details = um.get("input_token_details") or {}
    read, write = int(details.get("cache_read") or 0), int(details.get("cache_creation") or 0)
    return {"model": model, "inputTokens": max(0, int(um.get("input_tokens") or 0) - read - write),
            "outputTokens": int(um.get("output_tokens") or 0), "cacheReadTokens": read, "cacheWriteTokens": write}


def history_messages(history: list[dict[str, str]], history_turns: int) -> list[AnyMessage]:
    """Seeds a new thread from client-side history: plain text, strictly alternating, last N exchanges."""
    messages: list[AnyMessage] = []
    expected = "user"
    for turn in (history or [])[-history_turns * 2:]:
        role, content = turn.get("role"), (turn.get("content") or "").strip()
        if role != expected or not content:
            continue
        content = content[:4000]
        messages.append(HumanMessage(content) if role == "user" else AIMessage(content, name=FINAL))
        expected = "assistant" if expected == "user" else "user"
    if messages and isinstance(messages[-1], HumanMessage):
        messages.pop()
    return messages


def turn_input(question: str, history: Optional[list[dict[str, str]]] = None, history_turns: int = 6) -> AgentState:
    """Input for one turn: the new question (plus seeded history for a new thread) and reset per-turn fields."""
    seeded = history_messages(history or [], history_turns)
    return {**PER_TURN_DEFAULTS, "question": question,
            "messages": seeded + [HumanMessage(user_turn(question, date.today().isoformat()))]}


# ----------------------------------------------------------------------------------------------- nodes

@traced_node("prepare")
async def prepare_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    """Compacts earlier turns: keep user questions and final answers, drop the working (tool calls, results,
    drafts, revision prompts), and keep only the last N exchanges."""
    deps = _deps(config)
    messages = state.get("messages") or []
    prior, current = messages[:-1], messages[-1:]
    keep, remove = [], []
    for m in prior:
        is_question = isinstance(m, HumanMessage) and getattr(m, "name", None) != REVISION
        is_final = isinstance(m, AIMessage) and getattr(m, "name", None) == FINAL and not m.tool_calls
        (keep if (is_question or is_final) else remove).append(m)
    overflow = max(0, len(keep) - deps.limits.history_turns * 2)
    remove += keep[:overflow]
    if not remove or not current:
        return {}
    return {"messages": [RemoveMessage(id=m.id) for m in remove if getattr(m, "id", None)]}


@traced_node("agent")
async def agent_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    deps = _deps(config)
    lim = deps.limits
    if state.get("model_calls", 0) >= lim.max_model_calls:
        return {"fallback_reason": "model call limit reached"}
    if deps.elapsed() > lim.sync_budget_seconds:
        return {"fallback_reason": "time budget reached"}
    if state.get("model_calls", 0) == 0:
        _emit("status", {"stage": "planning", "message": "Thinking about how to answer"})

    if deps.mock:
        return await _mock_turn(state, deps)

    prompt = [SystemMessage(content=[{"type": "text", "text": SYSTEM, "cache_control": {"type": "ephemeral"}}])]
    prompt += state["messages"]
    guardrails = list(state.get("guardrails", []))
    model_name = deps.primary_name
    try:
        reply = await deps.primary.ainvoke(prompt)
    except Exception as e:  # noqa: BLE001 - classify, then fail over or fall back
        if not is_retryable_model_error(e) or deps.fallback is None:
            guardrails.append({"type": "model_unavailable", "detail": f"{deps.primary_name}: {type(e).__name__}"})
            return {"fallback_reason": "model unavailable", "guardrails": guardrails}
        guardrails.append({"type": "model_failover",
                           "detail": f"{deps.primary_name} unavailable ({type(e).__name__}), using {deps.fallback_name}"})
        _emit("status", {"stage": "failover", "message": "Primary model is busy; continuing on the fallback model"})
        model_name = deps.fallback_name
        try:
            reply = await deps.fallback.ainvoke(prompt)
        except Exception as e2:  # noqa: BLE001
            guardrails.append({"type": "model_unavailable", "detail": f"{deps.fallback_name}: {type(e2).__name__}"})
            return {"fallback_reason": "model unavailable", "guardrails": guardrails}

    if (reply.response_metadata or {}).get("stop_reason") == "max_tokens":
        guardrails.append({"type": "truncated", "detail": "answer hit max_tokens"})
    update: dict[str, Any] = {
        "messages": [reply],
        "model_calls": state.get("model_calls", 0) + 1,
        "usage": list(state.get("usage", [])) + [usage_of(reply, model_name)],
        "guardrails": guardrails,
        "model_used": state.get("model_used") or model_name,
    }
    if not reply.tool_calls:
        update["draft"] = text_of(reply)
    return update


async def _mock_turn(state: AgentState, deps: Deps) -> dict[str, Any]:
    """Deterministic stand-in for Claude that speaks the same tool protocol: search once, then answer extractively."""
    calls = state.get("model_calls", 0) + 1
    tokens = sum(len(str(m.content)) for m in state["messages"]) // 4
    usage = list(state.get("usage", [])) + [
        {"model": "mock", "inputTokens": tokens, "outputTokens": 40, "cacheReadTokens": 0, "cacheWriteTokens": 0}]
    if not any(isinstance(m, ToolMessage) for m in state["messages"]):
        call = {"name": "search_docs", "args": {"query": state["question"]}, "id": f"toolu_mock_{uuid.uuid4().hex[:8]}"}
        return {"messages": [AIMessage(content="", tool_calls=[call])], "model_calls": calls, "usage": usage,
                "model_used": "mock"}
    result = await deps.backend.extractive(state["question"], state.get("sources", []), None)
    text = result.get("text", "")
    return {"messages": [AIMessage(content=text)], "model_calls": calls, "usage": usage, "draft": text,
            "model_used": "mock"}


@traced_node("tools")
async def tools_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    deps = _deps(config)
    lim = deps.limits
    last = state["messages"][-1]
    sources = list(state.get("sources", []))
    by_key = {f'{p["docId"]}#{p["parentOrdinal"]}': p for p in sources}
    context_tokens = state.get("context_tokens", 0)
    considered = state.get("passages_considered", 0)
    tool_calls = state.get("tool_calls", 0)
    job_id = state.get("job_id")
    trace = list(state.get("trace", []))
    guardrails = list(state.get("guardrails", []))
    dropped = list(state.get("dropped", []))
    messages = []

    def register(passage: dict[str, Any]) -> Optional[dict[str, Any]]:
        """Stable numbering per section for the whole turn, within the context budget."""
        nonlocal context_tokens
        key = f'{passage["docId"]}#{passage["parentOrdinal"]}'
        if key in by_key:
            return by_key[key]
        tokens = len(passage.get("text", "")) // 4
        if context_tokens + tokens > lim.max_context_tokens:
            guardrails.append({"type": "context_budget", "detail": f"Stopped adding passages at {context_tokens} tokens"})
            return None
        context_tokens += tokens
        numbered = {**passage, "n": len(sources) + 1}
        sources.append(numbered)
        by_key[key] = numbered
        if numbered.get("flagged"):
            guardrails.append({"type": "prompt_injection_flagged", "detail": f'{numbered["docId"]} · {numbered["section"]}'})
        return numbered

    for call in last.tool_calls:
        name, args, started = call["name"], call.get("args") or {}, time.monotonic()
        tool_calls += 1
        if tool_calls > lim.max_tool_calls:
            messages.append(ToolMessage(BUDGET_MESSAGE, tool_call_id=call["id"], status="error"))
            continue
        error, summary = False, ""
        try:
            if name == "search_docs":
                query = str(args.get("query", "")).strip()
                if not query:
                    raise ValueError("missing required argument 'query'")
                _emit("status", {"stage": "searching", "message": f'Searching for "{query}"'})
                r = await deps.backend.search(args)
                considered += int(r.get("candidates", 0))
                denied = r.get("droppedByPermissionCheck") or []
                if denied:
                    dropped += denied
                    guardrails.append({"type": "acl_late_check_drop",
                                       "detail": f"{len(denied)} document(s) removed by live permission check"})
                shown = [p for p in (register(x) for x in r.get("passages", [])) if p is not None]
                if shown:
                    content = format_sources(shown)
                    summary = (f'{len(shown)} passages from {len({p["docId"] for p in shown})} documents'
                               f'{" (cache hit)" if r.get("cacheHit") else ""}, {r.get("millis", 0)} ms')
                else:
                    content, summary = NOT_FOUND_HINT, "no results"
            elif name == "fetch_doc":
                doc_id = str(args.get("doc_id", "")).strip()
                if not doc_id:
                    raise ValueError("missing required argument 'doc_id'")
                _emit("status", {"stage": "reading", "message": f"Reading {doc_id}"})
                r = await deps.backend.fetch(doc_id)
                shown = [p for p in (register(x) for x in r.get("passages", [])) if p is not None]
                if not r.get("found"):
                    # Same answer for "missing" and "no access": never confirm a document exists.
                    content, error, summary = json.dumps({"error_type": "not_found", "retryable": False,
                                                          "message": "No accessible document with that id."}), True, "not available"
                else:
                    content, summary = format_sources(shown), f"loaded {doc_id}"
            elif name == "start_summary_job":
                topic = str(args.get("topic", "")).strip()
                if not topic:
                    raise ValueError("missing required argument 'topic'")
                job = await deps.backend.start_job(topic)
                job_id = job["jobId"]
                _emit("job", job)
                content = json.dumps({"job_id": job_id, "status": job.get("status"),
                                      "reused_existing_job": job.get("reused", False),
                                      "note": "Running in the background. Tell the user it will appear in their Jobs panel."})
                summary = f'{"reused" if job.get("reused") else "queued"} job {job_id[:8]}'
            else:
                content, error, summary = json.dumps({"error_type": "unknown_tool", "retryable": False,
                                                      "message": f"No tool named {name}"}), True, "unknown tool"
        except ValueError as e:
            content, error, summary = json.dumps({"error_type": "invalid_arguments", "retryable": False,
                                                  "message": str(e)}), True, str(e)
        except BackendError as e:
            # Structured, secret-free error the model can act on (fix the arguments, try another tool, or answer).
            if e.status == 400:
                content = json.dumps({"error_type": "invalid_arguments", "retryable": False, "message": str(e)})
            else:
                content = json.dumps({"error_type": "tool_unavailable", "retryable": e.retryable,
                                      "message": f"The {name} tool is temporarily unavailable."})
            error, summary = True, f"failed: {e.status or 'unreachable'}"

        entry = {"tool": name, "input": args, "summary": summary,
                 "ms": int((time.monotonic() - started) * 1000), "error": error}
        trace.append(entry)
        _emit("tool", entry)
        messages.append(ToolMessage(content, tool_call_id=call["id"], status="error" if error else "success"))

    return {"messages": messages, "sources": sources, "context_tokens": context_tokens,
            "passages_considered": considered, "tool_calls": tool_calls, "job_id": job_id,
            "trace": trace, "guardrails": guardrails, "dropped": dropped}


@traced_node("verify")
async def verify_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    deps = _deps(config)
    draft = (state.get("draft") or "").strip()
    if not draft:
        return {"verdict": "fallback", "fallback_reason": "empty answer"}
    if not state.get("sources"):
        return {"verdict": "finalize"}          # nothing to check against ("not found", or a job was started)
    _emit("status", {"stage": "verifying", "message": "Checking every statement against its source"})
    report = await deps.backend.verify(draft, state["sources"])
    if report["failureRate"] <= report["maxFailureRate"]:
        return {"verdict": "finalize"}
    can_revise = (not state.get("revised") and not deps.mock
                  and deps.elapsed() < deps.limits.sync_budget_seconds)
    if can_revise:
        guardrails = list(state.get("guardrails", [])) + [{
            "type": "grounding_revision",
            "detail": f'{report["failingCount"]} of {report["factualCount"]} statements unsupported; asking the model to revise'}]
        return {"verdict": "revise", "revision_prompt": report["revisionPrompt"], "guardrails": guardrails}
    return {"verdict": "fallback", "fallback_reason": "answer failed the grounding check"}


@traced_node("revise")
async def revise_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    _emit("status", {"stage": "revising", "message": "Some statements weren't supported by the sources; revising"})
    return {"messages": [HumanMessage(state["revision_prompt"], name=REVISION)], "revised": True, "draft": None}


@traced_node("fallback")
async def fallback_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    """Quote the sources directly rather than risk a confident wrong answer."""
    deps = _deps(config)
    reason = state.get("fallback_reason") or "empty answer"
    sources = state.get("sources", [])
    r = await deps.backend.extractive(state["question"], sources, reason)
    update: dict[str, Any] = {"draft": r.get("text", ""), "used_fallback": True}
    if sources or reason != "model unavailable":
        update["guardrails"] = list(state.get("guardrails", [])) + [{"type": "extractive_fallback", "detail": reason}]
    return update


@traced_node("finalize")
async def finalize_node(state: AgentState, config: RunnableConfig) -> dict[str, Any]:
    """The output policy runs here; the user-facing answer becomes this turn's remembered reply."""
    deps = _deps(config)
    result = await deps.backend.finalize({
        "question": state["question"],
        "answer": state.get("draft") or "",
        "usedFallback": bool(state.get("used_fallback")),
        "sources": state.get("sources", []),
        "trace": state.get("trace", []),
        "guardrails": state.get("guardrails", []),
        "dropped": state.get("dropped", []),
        "usage": state.get("usage", []),
        "passagesConsidered": state.get("passages_considered", 0),
        "latencyMs": int(deps.elapsed() * 1000),
        "jobId": state.get("job_id"),
        "model": state.get("model_used") or deps.primary_name,
    })
    # Remembered without citation markers: next turn's sources are numbered afresh.
    remembered = re.sub(r"\s?\[\d{1,2}]", "", result.get("answer", ""))
    return {"result": result, "messages": [AIMessage(remembered, name=FINAL)]}


# ----------------------------------------------------------------------------------------------- edges

def route_after_agent(state: AgentState) -> str:
    if state.get("fallback_reason"):
        return "fallback"
    last = state["messages"][-1]
    if isinstance(last, AIMessage) and last.tool_calls:
        return "tools"
    return "verify"


def route_after_verify(state: AgentState) -> str:
    return state.get("verdict") or "finalize"


def build_graph(checkpointer: Any = None):
    g = StateGraph(AgentState)
    g.add_node("prepare", prepare_node)
    g.add_node("agent", agent_node)
    g.add_node("tools", tools_node)
    g.add_node("verify", verify_node)
    g.add_node("revise", revise_node)
    g.add_node("fallback", fallback_node)
    g.add_node("finalize", finalize_node)

    g.add_edge(START, "prepare")
    g.add_edge("prepare", "agent")
    g.add_conditional_edges("agent", route_after_agent, {"tools": "tools", "verify": "verify", "fallback": "fallback"})
    g.add_edge("tools", "agent")
    g.add_conditional_edges("verify", route_after_verify,
                            {"finalize": "finalize", "revise": "revise", "fallback": "fallback"})
    g.add_edge("revise", "agent")
    g.add_edge("fallback", "finalize")
    g.add_edge("finalize", END)
    return g.compile(checkpointer=checkpointer)
