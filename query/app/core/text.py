"""Tokenisation shared by the embedder, reranker, grounding check and extractive answerer.
Mirrors dev.docassist.common.util.Text: the query plane must tokenise exactly as ingestion did."""
import hashlib
import math
import re

# Java: [\p{L}\p{N}]+(?:[.,][0-9]+)*  — letters and digits, keeping 18.4 and 1,200 together.
_TOKEN = re.compile(r"[^\W_]+(?:[.,][0-9]+)*")
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\[(\"'*])")

STOPWORDS = frozenset("""
a an and are as at be by can do does for from get has have how i if in is it its may me much my of on or our should
that the their there this to was we what when where which who will with you your many any all about into than then
them they these those so not no yes after before per each also only must up out over under need tell please
""".split())


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def short_hash(text: str) -> str:
    return sha256(text)[:16]


def estimate_tokens(text: str) -> int:
    """~4 characters per token for English prose."""
    return max(1, math.ceil(len(text) / 4))


def tokens(text: str) -> list[str]:
    return [m.group().replace(",", "") for m in _TOKEN.finditer(text.lower())]


def stem(t: str) -> str:
    if len(t) > 5 and t.endswith("ing"):
        return t[:-3]
    if len(t) > 4 and t.endswith("ies"):
        return t[:-3] + "y"
    if len(t) > 4 and t.endswith("es") and not t.endswith("ses"):
        return t[:-1]
    if len(t) > 3 and t.endswith("s") and not t.endswith("ss"):
        return t[:-1]
    return t


def is_number(token: str) -> bool:
    return bool(token) and token[0].isdigit()


def terms(text: str) -> list[str]:
    """Lower-cased, stop-word-free, lightly stemmed content terms."""
    out = []
    for t in tokens(text):
        if t in STOPWORDS:
            continue
        if len(t) < 2 and not t[0].isdigit():
            continue
        out.append(stem(t))
    return out


def sentences(text: str) -> list[str]:
    out = []
    for line in re.split(r"\n+", text):
        trimmed = line.strip()
        if not trimmed:
            continue
        out.extend(s.strip() for s in _SENTENCE_END.split(trimmed) if s.strip())
    return out
