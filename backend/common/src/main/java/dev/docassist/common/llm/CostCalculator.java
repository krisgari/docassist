package dev.docassist.common.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts token usage to USD. Prices are configuration, not code: defaults below are the published list prices
 * when this was written (USD per million tokens) and should be replaced with the customer's contracted rates.
 */
public final class CostCalculator {

    /** inputPerMTok, outputPerMTok, cache-read multiplier of the input price, cache-write multiplier. */
    public record Price(double inputPerMTok, double outputPerMTok, double cacheReadMultiplier, double cacheWriteMultiplier) {}

    private final Map<String, Price> prices = new HashMap<>();
    private final Price fallback;

    public CostCalculator() {
        prices.put("claude-sonnet-5-5", new Price(2.0, 10.0, 0.05, 1.25));
        prices.put("claude-haiku-5-5", new Price(0.10, 0.50, 0.10, 1.25));
        prices.put("claude-opus-5-5", new Price(4.0, 20.0, 0.05, 1.25));
        prices.put("mock", new Price(0, 0, 0, 0));
        fallback = new Price(2.0, 10.0, 0.10, 1.25);
    }

    public void setPrice(String model, Price price) {
        prices.put(model, price);
    }

    public double cost(Usage u) {
        Price p = prices.getOrDefault(u.model(), fallback);
        double perIn = p.inputPerMTok() / 1_000_000.0;
        double perOut = p.outputPerMTok() / 1_000_000.0;
        return u.inputTokens() * perIn
                + u.cacheReadTokens() * perIn * p.cacheReadMultiplier()
                + u.cacheWriteTokens() * perIn * p.cacheWriteMultiplier()
                + u.outputTokens() * perOut;
    }
}
