package dev.docassist.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.docassist.common.config.DocassistProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Claude Messages API over plain HTTP (no SDK dependency, so every header and retry is visible in one place).
 *
 * <ul>
 *   <li>System prompt and tool definitions carry {@code cache_control} so the stable prefix is cached.</li>
 *   <li>429 / 5xx / 529 are retried with exponential backoff + jitter, honouring {@code retry-after}.</li>
 *   <li>4xx other than 429 fail fast (a bad request will not get better by retrying).</li>
 * </ul>
 */
public class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
    private static final Set<Integer> RETRYABLE = Set.of(408, 409, 429, 500, 502, 503, 504, 529);

    private final DocassistProperties.Llm cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AnthropicClient(DocassistProperties.Llm cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override public String name() { return "anthropic"; }
    @Override public String model() { return cfg.getModel(); }
    @Override public String fastModel() { return cfg.getFastModel(); }
    @Override public boolean isMock() { return false; }

    @Override
    public LlmResponse create(LlmRequest req) {
        String body = buildBody(req);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HttpRequest httpReq = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl() + "/v1/messages"))
                        .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                        .header("content-type", "application/json")
                        .header("x-api-key", cfg.getApiKey())
                        .header("anthropic-version", "2023-06-01")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status == 200) return parse(req.model(), resp.body());

                boolean retryable = RETRYABLE.contains(status);
                if (!retryable || attempt > cfg.getMaxRetries()) {
                    throw new LlmException("Claude API returned " + status + ": " + truncate(resp.body()),
                            status, retryable, null);
                }
                long waitMs = resp.headers().firstValue("retry-after")
                        .map(v -> parseRetryAfter(v)).orElse(backoff(attempt));
                log.warn("Claude API {} (attempt {}), retrying in {} ms", status, attempt, waitMs);
                sleep(waitMs);
            } catch (IOException e) {
                if (attempt > cfg.getMaxRetries()) {
                    throw new LlmException("Claude API unreachable: " + e.getMessage(), 0, true, e);
                }
                log.warn("Claude API I/O error (attempt {}): {}", attempt, e.getMessage());
                sleep(backoff(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted", 0, false, e);
            }
        }
    }

    private String buildBody(LlmRequest req) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", req.model());
        root.put("max_tokens", req.maxTokens() > 0 ? req.maxTokens() : cfg.getMaxTokens());

        ArrayNode system = root.putArray("system");
        ObjectNode sys = system.addObject();
        sys.put("type", "text");
        sys.put("text", req.system());
        sys.putObject("cache_control").put("type", "ephemeral");

        if (req.tools() != null && !req.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (int i = 0; i < req.tools().size(); i++) {
                ToolSpec t = req.tools().get(i);
                ObjectNode tool = tools.addObject();
                tool.put("name", t.name());
                tool.put("description", t.description());
                tool.set("input_schema", t.inputSchema());
                if (i == req.tools().size() - 1) tool.putObject("cache_control").put("type", "ephemeral");
            }
        }
        root.set("messages", req.messages());
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new LlmException("cannot serialize request", 0, false, e);
        }
    }

    private LlmResponse parse(String model, String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode u = root.path("usage");
        Usage usage = new Usage(root.path("model").asText(model),
                u.path("input_tokens").asInt(), u.path("output_tokens").asInt(),
                u.path("cache_read_input_tokens").asInt(), u.path("cache_creation_input_tokens").asInt());
        ArrayNode content = root.path("content").isArray() ? (ArrayNode) root.path("content") : mapper.createArrayNode();
        return new LlmResponse(content, root.path("stop_reason").asText("end_turn"), usage);
    }

    private static long backoff(int attempt) {
        long base = (long) (500 * Math.pow(2, attempt - 1));
        return base + ThreadLocalRandom.current().nextLong(0, base / 2 + 1);
    }

    private static long parseRetryAfter(String v) {
        try {
            return Math.min(20_000, (long) (Double.parseDouble(v) * 1000));
        } catch (NumberFormatException e) {
            return 2000;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
