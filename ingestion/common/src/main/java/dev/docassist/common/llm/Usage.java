package dev.docassist.common.llm;

/** Token usage for one model call, as reported by the provider. */
public record Usage(String model, int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {

    public static Usage zero(String model) {
        return new Usage(model, 0, 0, 0, 0);
    }

    public Usage plus(Usage o) {
        return new Usage(model, inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens);
    }
}
