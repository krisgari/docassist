"""Error types shared by the graph and its clients (kept free of third-party imports)."""


class BackendError(Exception):
    """A call to the docassist API failed. `retryable` is true for 5xx and connection errors."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.retryable = status == 0 or status >= 500 or status == 429


class RetryableModelError(Exception):
    """Raised by test doubles to simulate a provider outage (429 / 5xx / 529)."""


RETRYABLE_STATUS = {408, 409, 429, 500, 502, 503, 504, 529}


def is_retryable_model_error(exc: Exception) -> bool:
    if isinstance(exc, RetryableModelError):
        return True
    try:
        import anthropic
    except ImportError:  # pragma: no cover
        return False
    if isinstance(exc, (anthropic.APIConnectionError, anthropic.APITimeoutError)):
        return True
    if isinstance(exc, anthropic.APIStatusError):
        return exc.status_code in RETRYABLE_STATUS
    return False
