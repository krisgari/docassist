package dev.docassist.common.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.docassist.common.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic stand-in for Claude, used when no API key is configured and in CI. It follows the same tool-use
 * protocol as the real model: first turn calls {@code search_docs} with the question; once tool results are in,
 * it answers extractively with citations (or abstains). That lets the full agent loop, guardrails, audit,
 * evals and UI run offline, and gives the eval suite a stable baseline to compare the real model against.
 */
public class MockLlmClient implements LlmClient {

    private final ObjectMapper mapper;
    private final ExtractiveAnswerer answerer;

    public MockLlmClient(ObjectMapper mapper, ExtractiveAnswerer answerer) {
        this.mapper = mapper;
        this.answerer = answerer;
    }

    @Override public String name() { return "mock"; }
    @Override public String model() { return "mock"; }
    @Override public String fastModel() { return "mock"; }
    @Override public boolean isMock() { return true; }

    @Override
    public LlmResponse create(LlmRequest req) {
        String question = firstUserText(req.messages());
        List<SourceBlocks.Source> sources = new ArrayList<>();
        boolean sawToolResult = false;
        for (JsonNode msg : req.messages()) {
            if (!"user".equals(msg.path("role").asText()) || !msg.path("content").isArray()) continue;
            for (JsonNode block : msg.path("content")) {
                if ("tool_result".equals(block.path("type").asText())) {
                    sawToolResult = true;
                    sources.addAll(SourceBlocks.parse(toolResultText(block)));
                } else if ("text".equals(block.path("type").asText())) {
                    sources.addAll(SourceBlocks.parse(block.path("text").asText()));
                }
            }
        }

        ArrayNode content = mapper.createArrayNode();
        boolean wantsTools = req.tools() != null && req.tools().stream().anyMatch(t -> t.name().equals("search_docs"));
        if (wantsTools && !sawToolResult) {
            ObjectNode tu = content.addObject();
            tu.put("type", "tool_use");
            tu.put("id", "toolu_mock_" + UUID.randomUUID().toString().substring(0, 8));
            tu.put("name", "search_docs");
            tu.putObject("input").put("query", question);
            return new LlmResponse(content, "tool_use", usage(req, 40));
        }

        String text = answerer.answer(question, sources).text();
        content.addObject().put("type", "text").put("text", text);
        return new LlmResponse(content, "end_turn", usage(req, Text.estimateTokens(text)));
    }

    private Usage usage(LlmRequest req, int out) {
        int in = Text.estimateTokens(req.system()) + Text.estimateTokens(req.messages().toString());
        return new Usage("mock", in, out, 0, 0);
    }

    /** The current question: the most recent user message that has text (tool-result turns have none). */
    private static String firstUserText(ArrayNode messages) {
        String latest = "";
        for (JsonNode msg : messages) {
            if (!"user".equals(msg.path("role").asText())) continue;
            JsonNode c = msg.path("content");
            if (c.isTextual()) {
                latest = stripContext(c.asText());
                continue;
            }
            for (JsonNode block : c) {
                if ("text".equals(block.path("type").asText())) latest = stripContext(block.path("text").asText());
            }
        }
        return latest;
    }

    /** Prompts may wrap the question in a <question> tag; the mock only needs the question itself. */
    private static String stripContext(String text) {
        int s = text.indexOf("<question>");
        int e = text.indexOf("</question>");
        return s >= 0 && e > s ? text.substring(s + 10, e).trim() : text;
    }

    private static String toolResultText(JsonNode block) {
        JsonNode c = block.path("content");
        if (c.isTextual()) return c.asText();
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : c) sb.append(part.path("text").asText()).append('\n');
        return sb.toString();
    }
}
