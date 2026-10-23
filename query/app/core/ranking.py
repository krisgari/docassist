"""Reciprocal rank fusion and the heuristic reranker (mirrors Rrf.java and HeuristicReranker.java)."""
import math
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from typing import Optional

from .text import terms

RRF_K = 60


@dataclass(frozen=True)
class Passage:
    """A retrieved chunk with what reranking, permission checks and citations need."""
    doc_id: str
    ordinal: int
    parent_ordinal: int
    title: str
    url: str
    source_system: str
    section: str
    body: str
    status: str
    sensitivity: Optional[str]
    flagged: bool
    acl: tuple[str, ...]
    modified_at: Optional[datetime]
    fused: float = 0.0
    score: float = 0.0
    extra: dict = field(default_factory=dict, compare=False)

    @property
    def key(self) -> str:
        return f"{self.doc_id}#{self.ordinal}"

    @property
    def section_key(self) -> str:
        return f"{self.doc_id}#{self.parent_ordinal}"

    @property
    def modified(self) -> str:
        return self.modified_at.date().isoformat() if self.modified_at else ""

    def as_source(self, n: Optional[int] = None) -> dict:
        return {"n": n, "docId": self.doc_id, "parentOrdinal": self.parent_ordinal, "title": self.title,
                "url": self.url, "sourceSystem": self.source_system, "section": self.section,
                "modified": self.modified, "status": self.status, "flagged": self.flagged, "text": self.body}


def rrf(rankings: list[list[str]], k: int = RRF_K) -> dict[str, float]:
    scores: dict[str, float] = {}
    for ranking in rankings:
        for rank, key in enumerate(ranking):
            scores[key] = scores.get(key, 0.0) + 1.0 / (k + rank + 1)
    return dict(sorted(scores.items(), key=lambda kv: -kv[1]))   # stable, like Java's List.sort


def rerank(query: str, candidates: list[Passage], top_k: int, now: Optional[datetime] = None) -> list[Passage]:
    """Fused rank + query-term coverage + small recency boost; superseded and injection-flagged chunks penalised."""
    if not candidates:
        return []
    q_terms = list(dict.fromkeys(terms(query)))
    max_fused = max(c.fused for c in candidates) or 1.0
    reference = now or datetime.now(timezone.utc)
    scored = []
    for c in candidates:
        c_terms = set(terms(c.section + " " + c.body))
        coverage = (sum(1 for t in q_terms if t in c_terms) / len(q_terms)) if q_terms else 0.0
        fused_norm = c.fused / max_fused if max_fused > 0 else 0.0
        if c.modified_at is None:
            age_days = 3650
        else:
            age_days = int((reference - c.modified_at).total_seconds() / 86400)  # Java Duration.toDays truncates
        recency = 0.05 * math.exp(-max(0, age_days) / 365.0)
        score = 0.45 * fused_norm + 0.50 * coverage + recency
        if c.status == "superseded":
            score -= 0.30
        if c.flagged:
            score -= 0.20
        scored.append(replace(c, score=score))
    scored.sort(key=lambda p: -p.score)
    return scored[:top_k]
