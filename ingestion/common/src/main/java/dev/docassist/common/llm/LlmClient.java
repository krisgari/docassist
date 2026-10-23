package dev.docassist.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral Messages-style client. Implementations: Anthropic (real) and Mock (deterministic, offline). */
public interface LlmClient {

    LlmResponse create(LlmRequest request);

    String name();

    String model();

    String fastModel();

    boolean isMock();

    record ToolSpec(String name, String description, JsonNode inputSchema) {}

    /**
     * @param system   system prompt (cached as a stable prefix)
     * @param messages Messages API "messages" array (assistant turns are replayed verbatim, including thinking)
     */
    record LlmRequest(String model, String system, ArrayNode messages, List<ToolSpec> tools, int maxTokens) {}

    record ToolUse(String id, String name, JsonNode input) {}

    record LlmResponse(ArrayNode content, String stopReason, Usage usage) {

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
            }
            return sb.toString().trim();
        }

        public List<ToolUse> toolUses() {
            List<ToolUse> out = new ArrayList<>();
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    out.add(new ToolUse(block.path("id").asText(), block.path("name").asText(), block.path("input")));
                }
            }
            return out;
        }
    }

    /** Thrown after retries are exhausted. {@code retryable} tells the caller whether a fallback model may help. */
    class LlmException extends RuntimeException {
        private final int status;
        private final boolean retryable;

        public LlmException(String message, int status, boolean retryable, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.retryable = retryable;
        }

        public int status() { return status; }
        public boolean retryable() { return retryable; }
    }
}
