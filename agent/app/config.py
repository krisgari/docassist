"""Settings, read once from the environment. Defaults match the Java services' defaults."""
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    api_url: str
    internal_token: str
    anthropic_api_key: str
    provider: str
    model: str
    fast_model: str
    max_tokens: int = 8192
    max_model_calls: int = 6
    max_tool_calls: int = 8
    sync_budget_seconds: float = 25.0
    max_context_tokens: int = 6000
    history_turns: int = 6

    @property
    def use_mock(self) -> bool:
        """auto = Claude when a key is set, otherwise the deterministic mock (same as the Java services)."""
        if self.provider == "mock":
            return True
        if self.provider == "anthropic":
            if not self.anthropic_api_key:
                raise RuntimeError("DOCASSIST_LLM_PROVIDER=anthropic but ANTHROPIC_API_KEY is empty")
            return False
        return not self.anthropic_api_key


def load_settings() -> Settings:
    env = os.environ.get
    return Settings(
        api_url=env("DOCASSIST_API_URL", "http://localhost:8080").rstrip("/"),
        internal_token=env("DOCASSIST_INTERNAL_TOKEN", "dev-internal-token-change-me"),
        anthropic_api_key=env("ANTHROPIC_API_KEY", ""),
        provider=env("DOCASSIST_LLM_PROVIDER", "auto").lower(),
        model=env("DOCASSIST_LLM_MODEL", "claude-sonnet-5-5"),
        fast_model=env("DOCASSIST_LLM_FAST_MODEL", "claude-haiku-5-5"),
        max_model_calls=int(env("DOCASSIST_AGENT_MAX_MODEL_CALLS", "6")),
        max_tool_calls=int(env("DOCASSIST_AGENT_MAX_TOOL_CALLS", "8")),
        sync_budget_seconds=float(env("DOCASSIST_AGENT_SYNC_BUDGET_SECONDS", "25")),
        max_context_tokens=int(env("DOCASSIST_AGENT_MAX_CONTEXT_TOKENS", "6000")),
    )
