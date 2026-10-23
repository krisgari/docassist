"""Offline embedder, bit-exact with dev.docassist.common.embedding.HashingEmbedder.

Documents are embedded by the ingestion plane (Java) and queries by this plane (Python), so the two must produce
identical vectors for identical text. That reproduces Java's String.hashCode (UTF-16, int32 overflow), the int32
bit mixer, float32 accumulation and the float32 normalisation. tests/test_parity.py checks it against vectors
recorded from the Java class.
"""
import math
import struct

from .text import terms

DIMENSIONS = 512
MODEL_ID = "local-hash-v1-512"
_MASK = 0xFFFFFFFF


def _f32(x: float) -> float:
    return struct.unpack("f", struct.pack("f", x))[0]


def _signed(u: int) -> int:
    return u - (1 << 32) if u & 0x80000000 else u


def java_string_hash(s: str) -> int:
    h = 0
    data = s.encode("utf-16-be")
    for i in range(0, len(data), 2):
        h = (31 * h + ((data[i] << 8) | data[i + 1])) & _MASK
    return _signed(h)


def _mix(h: int) -> int:
    u = h & _MASK
    u ^= u >> 16
    u = (u * 0x85EBCA6B) & _MASK
    u ^= u >> 13
    u = (u * 0xC2B2AE35) & _MASK
    u ^= u >> 16
    return u  # unsigned view of the int32 result


def _add(v: list[float], feature: str, weight: float) -> None:
    u = _mix(java_string_hash(feature))
    bucket = _signed(u) % DIMENSIONS          # Java Math.floorMod on the signed int
    sign = 1.0 if ((u >> 16) & 1) == 0 else -1.0
    v[bucket] = _f32(v[bucket] + sign * weight)


def embed(text: str) -> list[float]:
    v = [0.0] * DIMENSIONS
    ts = terms(text)
    for i, t in enumerate(ts):
        _add(v, t, 1.0)
        if i + 1 < len(ts):
            _add(v, t + "_" + ts[i + 1], 0.5)
    norm = sum(x * x for x in v)
    if norm == 0:
        v[0] = 1.0
        return v
    inv = _f32(1.0 / math.sqrt(norm))
    return [_f32(x * inv) for x in v]


def to_pgvector(v: list[float]) -> str:
    return "[" + ",".join(repr(x) for x in v) + "]"
