"""Graph state. Only `messages` has a reducer (add_messages), so it persists across turns in the checkpointer and
supports RemoveMessage. Every other field is per-turn: each turn's input resets it, and nodes return full values."""
from typing import Annotated, Any, Optional, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages


class AgentState(TypedDict, total=False):
    messages: Annotated[list[AnyMessage], add_messages]   # the conversation, compacted between turns

    question: str
    sources: list[dict[str, Any]]          # numbered passages the model has seen this turn
    context_tokens: int
    passages_considered: int
    trace: list[dict[str, Any]]            # tool calls, for the UI and the audit log
    guardrails: list[dict[str, Any]]       # safeguards that fired
    dropped: list[str]                     # documents removed by the live permission check
    usage: list[dict[str, Any]]            # tokens per model call, priced at finalize

    model_calls: int
    tool_calls: int
    draft: Optional[str]
    revised: bool
    revision_prompt: Optional[str]
    verdict: Optional[str]                 # finalize | revise | fallback (set by verify)
    fallback_reason: Optional[str]
    used_fallback: bool
    job_id: Optional[str]
    model_used: Optional[str]
    result: Optional[dict[str, Any]]       # the user-facing answer produced by the output policy


PER_TURN_DEFAULTS: dict[str, Any] = {
    "sources": [], "context_tokens": 0, "passages_considered": 0, "trace": [], "guardrails": [], "dropped": [],
    "usage": [], "model_calls": 0, "tool_calls": 0, "draft": None, "revised": False, "revision_prompt": None,
    "verdict": None, "fallback_reason": None, "used_fallback": False, "job_id": None, "model_used": None,
    "result": None,
}
