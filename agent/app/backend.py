"""Client for the docassist Java API's internal agent endpoints.

Every call carries the end user's JWT (so permissions are checked as that user) and the service token (so only
this service can call these endpoints). The agent never touches the database, the index or the audit log directly.
"""
from typing import Any

import httpx

from .errors import BackendError


class Backend:
    def __init__(self, client: httpx.AsyncClient, base_url: str, service_token: str, user_token: str):
        self._client = client
        self._base = base_url.rstrip("/") + "/internal/agent"
        self._headers = {
            "Authorization": f"Bearer {user_token}",
            "X-Internal-Token": service_token,
        }

    async def _post(self, path: str, body: dict[str, Any], attempts: int = 2) -> dict[str, Any]:
        last: BackendError | None = None
        for _ in range(attempts):
            try:
                r = await self._client.post(self._base + path, json=body, headers=self._headers)
            except httpx.HTTPError as e:
                last = BackendError(0, f"docassist API unreachable: {e.__class__.__name__}")
                continue
            if r.status_code < 400:
                return r.json()
            message = r.text[:300]
            try:
                message = r.json().get("message", message)
            except ValueError:
                pass
            last = BackendError(r.status_code, message)
            if not last.retryable:
                break
        assert last is not None
        raise last

    async def search(self, args: dict[str, Any]) -> dict[str, Any]:
        return await self._post("/search", args)

    async def fetch(self, doc_id: str) -> dict[str, Any]:
        return await self._post("/fetch", {"docId": doc_id})

    async def start_job(self, topic: str) -> dict[str, Any]:
        # Not retried: job creation is idempotent server-side, but one attempt keeps the turn fast.
        return await self._post("/jobs", {"topic": topic}, attempts=1)

    async def verify(self, answer: str, sources: list[dict]) -> dict[str, Any]:
        return await self._post("/verify", {"answer": answer, "sources": sources})

    async def extractive(self, question: str, sources: list[dict], reason: str | None) -> dict[str, Any]:
        return await self._post("/extractive", {"question": question, "reason": reason, "sources": sources})

    async def finalize(self, payload: dict[str, Any]) -> dict[str, Any]:
        return await self._post("/finalize", payload)
