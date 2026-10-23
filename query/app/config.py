"""Query-plane settings, read once from the environment (names match the ingestion plane's where shared)."""
import os
from dataclasses import dataclass, field


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


@dataclass(frozen=True)
class AgentLimits:
    max_model_calls: int = 6
    max_tool_calls: int = 8
    sync_budget_seconds: float = 25.0
    max_context_tokens: int = 6000
    history_turns: int = 6
    grounding_max_failure_rate: float = 0.2


@dataclass(frozen=True)
class Settings:
    database_url: str
    redis_url: str
    kafka_bootstrap: str
    tenant: str
    jwt_secret: str
    demo_login_enabled: bool
    token_ttl_minutes: int
    anthropic_api_key: str
    llm_provider: str
    model: str
    fast_model: str
    max_tokens: int
    embeddings_provider: str
    voyage_api_key: str
    voyage_model: str
    dense_k: int
    lexical_k: int
    top_k: int
    retrieval_cache_ttl: int
    group_cache_ttl: int
    chat_per_minute: int
    checkpointer: bool
    low_information_terms: tuple[str, ...]
    limits: AgentLimits = field(default_factory=AgentLimits)

    @property
    def use_mock(self) -> bool:
        """auto = Claude when a key is set, otherwise the deterministic mock (same rule as the ingestion plane)."""
        if self.llm_provider == "mock":
            return True
        if self.llm_provider == "anthropic":
            if not self.anthropic_api_key:
                raise RuntimeError("DOCASSIST_LLM_PROVIDER=anthropic but ANTHROPIC_API_KEY is empty")
            return False
        return not self.anthropic_api_key


def load_settings() -> Settings:
    secret = _env("DOCASSIST_JWT_SECRET", "change-me-local-dev-secret-at-least-32-bytes!!")
    if len(secret.encode()) < 32:
        raise RuntimeError("DOCASSIST_JWT_SECRET must be at least 32 bytes")
    return Settings(
        database_url=_env("DOCASSIST_DATABASE_URL", "postgresql://docassist:docassist@localhost:5432/docassist"),
        redis_url=_env("DOCASSIST_REDIS_URL", "redis://localhost:6379/0"),
        kafka_bootstrap=_env("DOCASSIST_KAFKA_BOOTSTRAP", "localhost:9094"),
        tenant=_env("DOCASSIST_TENANT", "acme"),
        jwt_secret=secret,
        demo_login_enabled=_env("DOCASSIST_DEMO_LOGIN_ENABLED", "true").lower() == "true",
        token_ttl_minutes=int(_env("DOCASSIST_TOKEN_TTL_MINUTES", "480")),
        anthropic_api_key=_env("ANTHROPIC_API_KEY", ""),
        llm_provider=_env("DOCASSIST_LLM_PROVIDER", "auto").lower(),
        model=_env("DOCASSIST_LLM_MODEL", "claude-sonnet-5-5"),
        fast_model=_env("DOCASSIST_LLM_FAST_MODEL", "claude-haiku-5-5"),
        max_tokens=int(_env("DOCASSIST_LLM_MAX_TOKENS", "8192")),
        embeddings_provider=_env("DOCASSIST_EMBEDDINGS_PROVIDER", "local").lower(),
        voyage_api_key=_env("VOYAGE_API_KEY", ""),
        voyage_model=_env("DOCASSIST_VOYAGE_MODEL", "voyage-3.5-lite"),
        dense_k=int(_env("DOCASSIST_DENSE_K", "50")),
        lexical_k=int(_env("DOCASSIST_LEXICAL_K", "50")),
        top_k=int(_env("DOCASSIST_TOP_K", "8")),
        retrieval_cache_ttl=int(_env("DOCASSIST_RETRIEVAL_CACHE_TTL_SECONDS", "600")),
        group_cache_ttl=int(_env("DOCASSIST_GROUP_CACHE_TTL_SECONDS", "60")),
        chat_per_minute=int(_env("DOCASSIST_CHAT_PER_MINUTE", "30")),
        checkpointer=_env("DOCASSIST_CHECKPOINTER", "postgres").lower() == "postgres",
        low_information_terms=tuple(t.strip() for t in _env("DOCASSIST_LOW_INFORMATION_TERMS",
                                                             "halcyon,halcyon robotics").split(",") if t.strip()),
        limits=AgentLimits(
            max_model_calls=int(_env("DOCASSIST_AGENT_MAX_MODEL_CALLS", "6")),
            max_tool_calls=int(_env("DOCASSIST_AGENT_MAX_TOOL_CALLS", "8")),
            sync_budget_seconds=float(_env("DOCASSIST_AGENT_SYNC_BUDGET_SECONDS", "25")),
            max_context_tokens=int(_env("DOCASSIST_AGENT_MAX_CONTEXT_TOKENS", "6000")),
            history_turns=int(_env("DOCASSIST_AGENT_HISTORY_TURNS", "6")),
            grounding_max_failure_rate=float(_env("DOCASSIST_GROUNDING_MAX_FAILURE_RATE", "0.2")),
        ),
    )
