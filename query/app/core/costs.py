"""Token usage to USD. Prices are configuration: replace these list prices with the customer's contracted rates."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Price:
    input_per_mtok: float
    output_per_mtok: float
    cache_read_multiplier: float
    cache_write_multiplier: float


DEFAULT_PRICES = {
    "claude-sonnet-5-5": Price(2.0, 10.0, 0.05, 1.25),
    "claude-haiku-5-5": Price(0.10, 0.50, 0.10, 1.25),
    "claude-opus-5-5": Price(4.0, 20.0, 0.05, 1.25),
    "mock": Price(0, 0, 0, 0),
}
FALLBACK_PRICE = Price(2.0, 10.0, 0.10, 1.25)


def cost(model: str, input_tokens: int, output_tokens: int, cache_read: int = 0, cache_write: int = 0,
         prices: dict[str, Price] = DEFAULT_PRICES) -> float:
    p = prices.get(model, FALLBACK_PRICE)
    per_in = p.input_per_mtok / 1_000_000.0
    per_out = p.output_per_mtok / 1_000_000.0
    return (input_tokens * per_in
            + cache_read * per_in * p.cache_read_multiplier
            + cache_write * per_in * p.cache_write_multiplier
            + output_tokens * per_out)
