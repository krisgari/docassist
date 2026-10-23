"""Fixed-window per-user limit in Redis. Fails open if Redis is down (availability over strictness for a limiter)."""
import logging
import time

from .services import Services

log = logging.getLogger("docassist.ratelimit")


async def allow(svc: Services, tenant: str, user_id: str, bucket: str = "chat") -> bool:
    key = f"rl:{bucket}:{tenant}:{user_id}:{int(time.time() // 60)}"
    try:
        n = await svc.redis.incr(key)
        if n == 1:
            await svc.redis.expire(key, 70)
        return n <= svc.settings.chat_per_minute
    except Exception as e:  # noqa: BLE001
        log.debug("rate limiter unavailable: %s", e)
        return True
