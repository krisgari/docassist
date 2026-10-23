"""What the graph is allowed to do, bound to one user. The graph only ever calls these methods, so every
retrieval is permission-checked as that user and every answer goes through the output policy."""
import logging
from typing import Any, Optional

from .. import policy, tools
from ..core.guardrails import check_grounding, revision_prompt
from ..permissions import Principals
from ..services import Services
from .errors import BackendError

log = logging.getLogger("docassist.agent")


class LocalBackend:
    def __init__(self, svc: Services, principals: Principals):
        self.svc = svc
        self.p = principals

    async def _guard(self, coro):
        """Bad arguments surface to the model as invalid_arguments; infrastructure failures as tool_unavailable."""
        try:
            return await coro
        except ValueError:
            raise
        except Exception as e:  # noqa: BLE001
            log.warning("tool backend failure: %s", e, exc_info=True)
            raise BackendError(503, type(e).__name__) from e

    async def search(self, args: dict[str, Any]) -> dict[str, Any]:
        return await self._guard(tools.search_passages(self.svc, self.p, args))

    async def fetch(self, doc_id: str) -> dict[str, Any]:
        return await self._guard(tools.fetch_passages(self.svc, self.p, doc_id))

    async def start_job(self, topic: str) -> dict[str, Any]:
        return await self._guard(tools.start_summary_job(self.svc, self.p, topic))

    async def verify(self, answer: str, sources: list[dict]) -> dict[str, Any]:
        report = check_grounding(answer, policy.source_texts(sources))
        return {"failureRate": report.failure_rate, "factualCount": report.factual_count,
                "failingCount": report.failing_count,
                "failing": [{"sentence": s.sentence, "status": s.status.lower()} for s in report.failing()],
                "invalidCitations": report.invalid_citations,
                "maxFailureRate": self.svc.settings.limits.grounding_max_failure_rate,
                "revisionPrompt": revision_prompt(report)}

    async def extractive(self, question: str, sources: list[dict], reason: Optional[str]) -> dict[str, Any]:
        if reason is None:            # the mock model's answer: plain extractive text
            a = self.svc.answerer.answer(question, sources)
            return {"found": a.found, "text": a.text}
        text = policy.fallback_answer(self.svc, question, sources, reason)
        return {"found": text not in (policy.NOT_FOUND, policy.UNAVAILABLE), "text": text}

    async def finalize(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await policy.finalize(self.svc, self.p, payload)
