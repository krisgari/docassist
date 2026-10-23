"""Authentication. Tenant and user identity always come from a verified token, never from a request body or a prompt.

The demo signs its own HS256 tokens so the UI can switch between seeded employees. In a customer deployment, set
DOCASSIST_DEMO_LOGIN_ENABLED=false and verify the IdP's tokens instead (Okta / Entra JWKS; see README)."""
import time
from dataclasses import dataclass
from typing import Optional

import jwt
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel

ISSUER = "docassist-demo"


@dataclass(frozen=True)
class CurrentUser:
    tenant_id: str
    user_id: str
    name: str
    token: str


def decode(token: str, secret: str) -> CurrentUser:
    try:
        claims = jwt.decode(token, secret, algorithms=["HS256"], issuer=ISSUER, options={"require": ["exp", "sub"]})
    except jwt.PyJWTError as e:
        raise HTTPException(status_code=401, detail="invalid or expired token") from e
    tenant = claims.get("tenant")
    if not tenant:
        raise HTTPException(status_code=401, detail="token has no tenant claim")
    return CurrentUser(tenant, claims["sub"], claims.get("name") or claims["sub"], token)


def bearer(value: Optional[str]) -> Optional[str]:
    if value and value.lower().startswith("bearer "):
        return value[7:].strip()
    return None


async def current_user(request: Request, authorization: Optional[str] = Header(default=None)) -> CurrentUser:
    token = bearer(authorization)
    if not token:
        raise HTTPException(status_code=401, detail="sign in required")
    return decode(token, request.app.state.svc.settings.jwt_secret)


router = APIRouter()


class LoginIn(BaseModel):
    userId: str


async def _demo_users(request: Request) -> list[dict]:
    svc = request.app.state.svc
    if not svc.settings.demo_login_enabled:
        raise HTTPException(status_code=404)
    async with svc.pool.connection() as conn:
        rows = await (await conn.execute(
            """SELECT u.user_id, u.display_name, u.title,
                      coalesce(array_agg(g.group_id ORDER BY g.group_id) FILTER (WHERE g.group_id IS NOT NULL), '{}') AS groups
               FROM app_user u LEFT JOIN group_member g ON g.tenant_id = u.tenant_id AND g.user_id = u.user_id
               WHERE u.tenant_id = %s GROUP BY u.user_id, u.display_name, u.title ORDER BY u.user_id""",
            (svc.settings.tenant,))).fetchall()
    return [{"userId": r["user_id"], "name": r["display_name"], "title": r["title"], "groups": list(r["groups"])}
            for r in rows]


@router.get("/auth/demo-users")
async def demo_users(request: Request) -> list[dict]:
    return await _demo_users(request)


@router.post("/auth/demo-login")
async def demo_login(body: LoginIn, request: Request) -> dict:
    svc = request.app.state.svc
    user = next((u for u in await _demo_users(request) if u["userId"] == body.userId), None)
    if user is None:
        raise HTTPException(status_code=401, detail="unknown user")
    now = int(time.time())
    token = jwt.encode({"iss": ISSUER, "sub": user["userId"], "iat": now,
                        "exp": now + svc.settings.token_ttl_minutes * 60,
                        "tenant": svc.settings.tenant, "name": user["name"]}, svc.settings.jwt_secret, algorithm="HS256")
    return {"token": token, "user": user}


@router.get("/api/me")
async def me(user: CurrentUser = Depends(current_user)) -> dict:
    # Groups are deliberately not in the token: they are resolved fresh (short cache) on each request.
    return {"userId": user.user_id, "name": user.name, "tenant": user.tenant_id}
