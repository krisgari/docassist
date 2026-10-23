"""Permission enforcement, in two layers:

1. principals_for() builds the ACL filter used *inside* the search SQL (never a post-filter).
2. late_check() re-validates the final passages against the source-of-truth ACL right before the model sees them.
   In the demo that is the `document` table the connector maintains; with SharePoint or Drive it is a batched call
   to the source system's permission API.
"""
import logging
from dataclasses import dataclass

from .core.text import short_hash
from .services import Services, redis_get, redis_set

log = logging.getLogger("docassist.permissions")
PUBLIC = "org:public"


@dataclass(frozen=True)
class Principals:
    tenant_id: str
    user_id: str
    groups: tuple[str, ...]

    @property
    def all(self) -> list[str]:
        return [f"user:{self.user_id}", *[f"group:{g}" for g in self.groups], PUBLIC]

    @property
    def acl_fingerprint(self) -> str:
        """Users with identical groups share a fingerprint, so they can share permission-safe cache entries."""
        return short_hash(f"{self.tenant_id}|{','.join(self.groups)}|{PUBLIC}")

    def can_see(self, acl: list[str]) -> bool:
        for p in acl:
            if p == f"user:{self.user_id}" or p == PUBLIC:
                return True
            if p.startswith("group:") and p[6:] in self.groups:
                return True
        return False


async def principals_for(svc: Services, tenant: str, user_id: str) -> Principals:
    key = f"groups:{tenant}:{user_id}"            # same key and format as the ingestion plane
    cached = await redis_get(svc, key)
    if cached is not None:
        groups = [g for g in cached.split(",") if g]
    else:
        async with svc.pool.connection() as conn:
            rows = await (await conn.execute(
                "SELECT group_id FROM group_member WHERE tenant_id = %s AND user_id = %s ORDER BY group_id",
                (tenant, user_id))).fetchall()
        groups = [r["group_id"] for r in rows]
        await redis_set(svc, key, ",".join(groups), svc.settings.group_cache_ttl)
    return Principals(tenant, user_id, tuple(sorted(set(groups))))


async def late_check(svc: Services, p: Principals, doc_ids: list[str]) -> tuple[list[str], list[str]]:
    """Returns (allowed, denied). A document that is missing or deleted counts as denied."""
    if not doc_ids:
        return [], []
    async with svc.pool.connection() as conn:
        rows = await (await conn.execute(
            "SELECT doc_id, acl_principals, status FROM document WHERE tenant_id = %s AND doc_id = ANY(%s)",
            (p.tenant_id, list(doc_ids)))).fetchall()
    by_id = {r["doc_id"]: r for r in rows}
    allowed, denied = [], []
    for d in dict.fromkeys(doc_ids):
        r = by_id.get(d)
        ok = r is not None and r["status"] != "deleted" and p.can_see(list(r["acl_principals"]))
        (allowed if ok else denied).append(d)
    if denied:
        # Index said visible, source says no: ACL drift. Logged for re-sync and the ops view.
        log.warning("acl_drift tenant=%s user=%s docs=%s", p.tenant_id, p.user_id, denied)
    return allowed, denied
