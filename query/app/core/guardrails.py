"""Prompt-injection scanner, secret/PII redactor and grounding checker (mirror the Java guardrails package)."""
import re
from dataclasses import dataclass

from .text import is_number, sentences, terms

# ------------------------------------------------------------------ prompt injection

_INJECTION = {
    "ignore_instructions": re.compile(
        r"\b(ignore|disregard|forget)\b.{0,20}\b(previous|prior|above|earlier|all)\b.{0,20}\b(instructions|rules|prompts?)\b", re.I),
    "role_override": re.compile(
        r"\byou are now\b|\b(admin|developer|god|jailbreak) mode\b|\bact as (an? )?(admin|administrator|system)\b", re.I),
    "prompt_exfiltration": re.compile(
        r"\b(reveal|print|show|repeat)\b.{0,20}\b(system prompt|your instructions|hidden instructions)\b", re.I),
    "privilege_claim": re.compile(r"\baccess (has been|is) (upgraded|elevated|granted)\b", re.I),
    "addressed_to_ai": re.compile(r"\b(note|message|instructions?) (for|to) (the )?(ai|assistant|llm|chatbot|model)s?\b", re.I),
    "hidden_characters": re.compile("[​-‍⁠﻿]"),
}


def injection_hits(text: str) -> list[str]:
    return [name for name, pattern in _INJECTION.items() if pattern.search(text)]


def is_suspicious(text: str) -> bool:
    return bool(injection_hits(text))


# ------------------------------------------------------------------ secrets and PII

_PII = {
    "aws_access_key": re.compile(r"\b(AKIA|ASIA)[0-9A-Z]{16}\b"),
    "aws_secret_key": re.compile(
        r"(?<![A-Za-z0-9/+])(?=[A-Za-z0-9/+]*[a-z])(?=[A-Za-z0-9/+]*[A-Z])(?=[A-Za-z0-9/+]*[0-9/+])[A-Za-z0-9/+]{40}(?![A-Za-z0-9/+=])"),
    "api_key": re.compile(r"\b(sk|pk|rk)[-_](live|test|ant|proj)?[-_]?[A-Za-z0-9_-]{20,}\b"),
    "bearer_token": re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/-]{20,}=*"),
    "private_key": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
    "us_ssn": re.compile(r"\b(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}\b"),
}
_CARD = re.compile(r"\b(?:\d[ -]?){12,18}\d\b")


def _luhn(digits: str) -> bool:
    total, double = 0, False
    for ch in reversed(digits):
        d = int(ch)
        if double:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        double = not double
    return total % 10 == 0


def redact(text: str) -> tuple[str, list[str]]:
    """Returns (redacted text, redacted types)."""
    found: list[str] = []
    for name, pattern in _PII.items():
        if pattern.search(text):
            found.append(name)
            text = pattern.sub(f"[REDACTED {name}]", text)
    card_found = False

    def card(m: re.Match) -> str:
        nonlocal card_found
        digits = re.sub(r"[ -]", "", m.group())
        if 13 <= len(digits) <= 19 and _luhn(digits):
            card_found = True
            return "[REDACTED card_number]"
        return m.group()

    replaced = _CARD.sub(card, text)
    if card_found:
        found.append("card_number")
        text = replaced
    return text, found


# ------------------------------------------------------------------ grounding

_CITE = re.compile(r"\[(\d{1,2})]")

SUPPORTED, WEAK, UNSUPPORTED, UNCITED, NON_FACTUAL = "SUPPORTED", "WEAK", "UNSUPPORTED", "UNCITED", "NON_FACTUAL"


@dataclass
class SentenceCheck:
    sentence: str
    citations: list[int]
    status: str
    term_support: float


@dataclass
class GroundingReport:
    sentences: list[SentenceCheck]
    invalid_citations: list[int]

    @property
    def factual_count(self) -> int:
        return sum(1 for s in self.sentences if s.status != NON_FACTUAL)

    @property
    def failing_count(self) -> int:
        return sum(1 for s in self.sentences if s.status in (UNSUPPORTED, UNCITED))

    @property
    def failure_rate(self) -> float:
        n = self.factual_count
        return 0.0 if n == 0 else self.failing_count / n

    def failing(self) -> list[SentenceCheck]:
        return [s for s in self.sentences if s.status in (UNSUPPORTED, UNCITED)]


_NON_FACTUAL_PREFIXES = ("i couldn't", "i could not", "i can't", "let me know", "if you", "note:", "sources", "#")


def _is_factual(sentence: str, ts: list[str], has_number: bool) -> bool:
    if has_number:
        return True
    s = sentence.strip().lower()
    if s.endswith("?") or s.endswith(":"):
        return False
    if s.startswith(_NON_FACTUAL_PREFIXES):
        return False
    return len(ts) >= 5


def check_grounding(answer: str, sources: dict[int, str], support_threshold: float = 0.6) -> GroundingReport:
    """A sentence is supported when all its numbers and most of its content terms appear in the passages it cites."""
    out: list[SentenceCheck] = []
    invalid: list[int] = []
    last_citation = None
    for sentence in sentences(answer):
        cites: list[int] = []
        for m in _CITE.finditer(sentence):
            n = int(m.group(1))
            if n in sources:
                cites.append(n)
            elif n not in invalid:
                invalid.append(n)
        clean = _CITE.sub(" ", sentence)
        ts = terms(clean)
        has_number = any(is_number(t) for t in ts)
        if not _is_factual(clean, ts, has_number):
            out.append(SentenceCheck(sentence, cites, NON_FACTUAL, 1.0))
            continue
        effective = [last_citation] if (not cites and last_citation is not None and sentence.startswith("-")) else cites
        if not effective:
            out.append(SentenceCheck(sentence, cites, UNCITED, 0.0))
            continue
        support: set[str] = set()
        for c in effective:
            support.update(terms(sources[c]))
        hit, numbers_ok = 0, True
        for t in ts:
            if t in support:
                hit += 1
            elif is_number(t):
                numbers_ok = False
        ratio = 1.0 if not ts else hit / len(ts)
        if not numbers_ok:
            status = UNSUPPORTED
        elif ratio >= support_threshold:
            status = SUPPORTED
        elif ratio >= support_threshold / 2:
            status = WEAK
        else:
            status = UNSUPPORTED
        out.append(SentenceCheck(sentence, cites, status, ratio))
        if cites:
            last_citation = cites[-1]
    return GroundingReport(out, sorted(invalid))


def revision_prompt(report: GroundingReport) -> str:
    listing = "".join(f"- {s.sentence} ({s.status.lower()})\n" for s in report.failing())
    return ("Some statements in your answer are not supported by the sources you cited:\n"
            f"{listing}\n"
            "Rewrite the answer using only what the numbered sources say. Cite every factual sentence. If the "
            "sources don't support a point, leave it out and say what you couldn't find.")
