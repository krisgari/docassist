"""Deterministic answerer (mirrors ExtractiveAnswerer.java).

Used by the mock model and as the last-resort fallback when an answer fails the grounding check twice: it returns
the best-matching source sentences verbatim, each with its citation, and abstains when nothing matches well.
"""
import re
from dataclasses import dataclass
from typing import Iterable

from .guardrails import is_suspicious
from .text import is_number, sentences, terms

NOT_FOUND = "I couldn't find this in the documents you have access to."

_RANGE = re.compile(r"(\d+(?:\.\d+)?)%?\s*(?:to|-|–)\s*(\d+(?:\.\d+)?)%?")


@dataclass
class Answer:
    text: str
    found: bool
    cited: list[int]


def _matches(term: str, term_set: set[str]) -> bool:
    """Exact match, or a shared 5-character prefix for longer words (approve / approver)."""
    if term in term_set:
        return True
    if len(term) < 5 or is_number(term):
        return False
    prefix = term[:5]
    return any(len(t) >= 5 and t.startswith(prefix) for t in term_set)


def _in_range(term: str, sentence: str) -> bool:
    """A number in the question falls inside a range stated in the sentence ("25" in "20% to 30%")."""
    if not is_number(term):
        return False
    try:
        v = float(term)
    except ValueError:
        return False
    for m in _RANGE.finditer(sentence):
        lo, hi = float(m.group(1)), float(m.group(2))
        if lo < hi and lo <= v <= hi:
            return True
    return False


def _cells(line: str):
    t = line.strip()
    if not t.startswith("|"):
        return None
    inner = t[1:-1] if t.endswith("|") else t[1:]
    return [c.strip() for c in inner.split("|")]


def _is_separator(line: str) -> bool:
    return not line.replace("|", "").replace("-", "").replace(":", "").strip()


def _row_as_sentence(header: list[str], row: list[str]) -> str:
    parts = []
    for i, cell in enumerate(row):
        h = header[i] if i < len(header) else ""
        parts.append(cell if not h else f"{h}: {cell}")
    return "; ".join(parts) + "."


class ExtractiveAnswerer:
    def __init__(self, low_information_terms: Iterable[str] = ()):
        stems: set[str] = set()
        for t in low_information_terms:
            stems.update(terms(t))
        self.low_information = frozenset(stems)

    def answer(self, question: str, sources: list[dict]) -> Answer:
        q = [t for t in dict.fromkeys(terms(question)) if t not in self.low_information]
        if not q or not sources:
            return Answer(NOT_FOUND, False, [])

        cands = []   # (score adjusted, order, n, sentence, superseded, raw score)
        order = 0
        for s in sources:
            if s.get("flagged"):
                continue
            section_terms = set(terms(s.get("section", "")))
            header = None
            for line in str(s.get("text", "")).split("\n"):
                cells = _cells(line)
                if cells is None:
                    header = None
                    source_text = line.strip()
                elif header is None:
                    header = cells
                    continue
                elif _is_separator(line):
                    continue
                else:
                    source_text = _row_as_sentence(header, cells)
                for sent in sentences(source_text):
                    if is_suspicious(sent) or sent.startswith("|"):
                        continue
                    st = set(terms(sent))
                    hit = section_hit = 0
                    for t in q:
                        if _matches(t, st) or _in_range(t, sent):
                            hit += 1
                        elif _matches(t, section_terms):
                            section_hit += 1
                    if hit == 0:
                        continue
                    score = (hit + 0.5 * section_hit) / len(q)
                    superseded = s.get("status") == "superseded"
                    cands.append((score - (0.25 if superseded else 0), order, s["n"], sent, superseded, score))
                    order += 1
        cands.sort(key=lambda c: (-c[0], c[1]))
        if not cands or cands[0][5] < 0.4:
            return Answer(NOT_FOUND, False, [])

        best = cands[0][5]
        picked = []
        for c in cands:
            if len(picked) == 3:
                break
            if c[5] < best * 0.6:
                break
            if any(p[3] == c[3] for p in picked):
                continue
            picked.append(c)
        picked.sort(key=lambda c: c[1])

        parts, cited, used_superseded = [], [], False
        for _, _, n, sent, superseded, _ in picked:
            s = sent if sent.endswith(".") else sent + "."
            parts.append(f"{s[:-1]} [{n}].")
            if n not in cited:
                cited.append(n)
            used_superseded |= superseded
        text = " ".join(parts)
        if used_superseded:
            text += "\n\nNote: part of this comes from a document marked as superseded; check the current version."
        return Answer(text, True, cited)
