"""Long-lived connections shared by every request: Postgres pool, Redis, Kafka producer, embedder, answerer."""
import logging
from dataclasses import dataclass
from typing import Any, Optional

from .config import Settings
from .core.extractive import ExtractiveAnswerer

log = logging.getLogger("docassist.query")


@dataclass
class Services:
    settings: Settings
    pool: Any                 # psycopg_pool.AsyncConnectionPool (autocommit, dict rows)
    redis: Any                # redis.asyncio.Redis (decode_responses=True)
    producer: Any             # aiokafka.AIOKafkaProducer
    embedder: Any             # app.embeddings.Embedder
    answerer: ExtractiveAnswerer
    iterative_scan: bool = False
    http: Optional[Any] = None   # httpx.AsyncClient for Voyage


async def redis_get(svc: Services, key: str) -> Optional[str]:
    """Redis is a cache: when it is down, requests carry on without it."""
    try:
        return await svc.redis.get(key)
    except Exception as e:  # noqa: BLE001
        log.debug("redis get %s failed: %s", key, e)
        return None


async def redis_set(svc: Services, key: str, value: str, ttl_seconds: int) -> None:
    try:
        await svc.redis.set(key, value, ex=ttl_seconds)
    except Exception as e:  # noqa: BLE001
        log.debug("redis set %s failed: %s", key, e)
