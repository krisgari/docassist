"""Graph state. Fields with a reducer accumulate across nodes; the rest are replaced by whichever node sets them."""
import operator
from typing import Annotated, Any, Optional, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages


class AgentState(TypedDict, total=False):
    question: str
    messages: Annotated[list[AnyMessage], add_messages]

    # Numbered passages the model has seen this turn: {"n", "docId", "parentOrdinal", "title", ...}
    sources: list[dict[str, Any]]
    context_tokens: int
    passages_considered: int

    # Accumulated for the UI trace, the audit log and cost accounting
    trace: Annotated[list[dict[str, Any]], operator.add]
    guardrails: Annotated[list[dict[str, Any]], operator.add]
    dropped: Annotated[list[str], operator.add]
    usage: Annotated[list[dict[str, Any]], operator.add]

    # Control
    model_calls: int
    tool_calls: int
    draft: Optional[str]
    revised: bool
    revision_prompt: Optional[str]
    verdict: Optional[str]             # finalize | revise | fallback (set by the verify node)
    fallback_reason: Optional[str]
    used_fallback: bool
    job_id: Optional[str]
    model_used: Optional[str]

    # Output of the finalize node: the API's AgentModels.Result
    result: Optional[dict[str, Any]]
