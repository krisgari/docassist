"""Query embeddings. They must come from the same model the ingestion plane used for documents:
`local` is the bit-exact port of the Java HashingEmbedder; `voyage` calls the same Voyage model on both sides."""
import base64
import logging
import struct

from .core import hashing_embedder
from .core.text import short_hash
from .services import Services, redis_get, redis_set

log = logging.getLogger("docassist.embeddings")


class Embedder:
    def __init__(self, provider: str, voyage_api_key: str = "", voyage_model: str = "voyage-3.5-lite"):
        if provider == "voyage" and not voyage_api_key:
            raise RuntimeError("DOCASSIST_EMBEDDINGS_PROVIDER=voyage but VOYAGE_API_KEY is empty")
        self.provider = provider
        self.voyage_api_key = voyage_api_key
        self.voyage_model = voyage_model

    @property
    def model_id(self) -> str:
        return (f"{self.voyage_model}-{hashing_embedder.DIMENSIONS}" if self.provider == "voyage"
                else hashing_embedder.MODEL_ID)

    async def embed_query(self, svc: Services, text: str) -> list[float]:
        key = f"emb:{self.model_id}:{short_hash(text)}"   # same key and encoding as the Java CachingEmbedder
        cached = await redis_get(svc, key)
        if cached:
            raw = base64.b64decode(cached)
            return list(struct.unpack(f">{len(raw) // 4}f", raw))
        vector = await self._embed(svc, text)
        await redis_set(svc, key, base64.b64encode(struct.pack(f">{len(vector)}f", *vector)).decode(), 7 * 86400)
        return vector

    async def _embed(self, svc: Services, text: str) -> list[float]:
        if self.provider != "voyage":
            return hashing_embedder.embed(text)
        r = await svc.http.post(
            "https://api.voyageai.com/v1/embeddings",
            headers={"Authorization": f"Bearer {self.voyage_api_key}"},
            json={"model": self.voyage_model, "input_type": "query", "input": [text],
                  "output_dimension": hashing_embedder.DIMENSIONS},
            timeout=30)
        r.raise_for_status()
        return [float(x) for x in r.json()["data"][0]["embedding"]]
