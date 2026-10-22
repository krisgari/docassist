package dev.docassist.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.docassist.api.audit.AuditService;
import dev.docassist.api.security.CurrentUser;
import dev.docassist.common.guardrails.GroundingChecker;
import dev.docassist.common.guardrails.PiiRedactor;
import dev.docassist.common.llm.CostCalculator;
import dev.docassist.common.llm.ExtractiveAnswerer;
import dev.docassist.common.llm.LlmClient;
import dev.docassist.common.llm.Usage;
import dev.docassist.common.retrieval.RetrievedChunk;
import dev.docassist.common.security.PermissionService;
import dev.docassist.common.security.Principals;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent loop: Claude plans, calls tools, reads results, and answers with citations. Around the loop:
 * hard limits (model calls, tool calls, wall clock), model failover, a grounding check with one revision,
 * an extractive fallback, PII redaction, cost accounting and an audit record.
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final Pattern CITE = Pattern.compile("\\[(\\d{1,2})]");

    private final LlmClient llm;
    private final AgentTools tools;
    private final PermissionService permissions;
    private final GroundingChecker grounding;
    private final PiiRedactor redactor;
    private final CostCalculator costs;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final ExtractiveAnswerer extractive;

    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long syncBudgetMs;
    private final double maxGroundingFailure;
    private final int historyTurns;

    public AgentService(LlmClient llm, AgentTools tools, PermissionService permissions, GroundingChecker grounding,
                        PiiRedactor redactor, CostCalculator costs, AuditService audit, ObjectMapper mapper,
                        MeterRegistry meters, ExtractiveAnswerer extractive,
                        @Value("${docassist.agent.max-model-calls}") int maxModelCalls,
                        @Value("${docassist.agent.max-tool-calls}") int maxToolCalls,
                        @Value("${docassist.agent.sync-budget-seconds}") int syncBudgetSeconds,
                        @Value("${docassist.agent.grounding-max-failure-rate}") double maxGroundingFailure,
                        @Value("${docassist.agent.history-turns}") int historyTurns) {
        this.llm = llm;
        this.tools = tools;
        this.permissions = permissions;
        this.grounding = grounding;
        this.redactor = redactor;
        this.costs = costs;
        this.audit = audit;
        this.mapper = mapper;
        this.meters = meters;
        this.extractive = extractive;
        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
        this.syncBudgetMs = syncBudgetSeconds * 1000L;
        this.maxGroundingFailure = maxGroundingFailure;
        this.historyTurns = historyTurns;
    }

    public AgentModels.Result run(CurrentUser user, String question, List<AgentModels.Turn> history,
                                  AgentModels.Events events) {
        long start = System.currentTimeMillis();
        Principals principals = permissions.principalsFor(user.tenantId(), user.userId());
        TurnState st = new TurnState(principals);
        List<LlmClient.ToolSpec> toolSpecs = tools.specs();

        ArrayNode messages = mapper.createArrayNode();
        appendHistory(messages, history);
        messages.addObject().put("role", "user").put("content", Prompts.userTurn(question, LocalDate.now().toString()));

        events.status("planning", "Thinking about how to answer");
        String finalText = null;
        String fallbackReason = null;
        int modelCalls = 0;

        // ---------------- the loop ----------------
        while (true) {
            if (modelCalls >= maxModelCalls) { fallbackReason = "model call limit reached"; break; }
            if (System.currentTimeMillis() - start > syncBudgetMs) { fallbackReason = "time budget reached"; break; }

            LlmClient.LlmResponse r;
            try {
                r = callWithFailover(messages, toolSpecs, st, events);
            } catch (LlmClient.LlmException e) {
                st.guardrail("model_unavailable", e.getMessage());
                fallbackReason = "model unavailable";
                break;
            }
            modelCalls++;
            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.set("content", r.content());

            if (!"tool_use".equals(r.stopReason())) {
                finalText = r.text();
                if ("max_tokens".equals(r.stopReason())) st.guardrail("truncated", "answer hit max_tokens");
                break;
            }

            ArrayNode results = mapper.createArrayNode();
            for (LlmClient.ToolUse tu : r.toolUses()) {
                ObjectNode res = results.addObject();
                res.put("type", "tool_result");
                res.put("tool_use_id", tu.id());
                if (++st.toolCalls > maxToolCalls) {
                    res.put("content", "Tool budget for this question is used up. Answer with the sources you have.");
                    res.put("is_error", true);
                    continue;
                }
                AgentTools.Outcome out = tools.execute(tu.name(), tu.input(), st, events);
                res.put("content", out.content());
                if (out.error()) res.put("is_error", true);
            }
            ObjectNode toolTurn = messages.addObject();
            toolTurn.put("role", "user");
            toolTurn.set("content", results);
        }

        // ---------------- verification ----------------
        boolean usedFallback = false;
        if (finalText == null || finalText.isBlank()) {
            finalText = fallbackAnswer(question, st, fallbackReason == null ? "empty answer" : fallbackReason);
            usedFallback = true;
        } else if (!st.sources.isEmpty()) {
            events.status("verifying", "Checking every statement against its source");
            GroundingChecker.Report report = grounding.check(finalText, st.sourceTexts());
            if (report.failureRate() > maxGroundingFailure && !llm.isMock()
                    && System.currentTimeMillis() - start < syncBudgetMs) {
                st.guardrail("grounding_revision", report.failingCount() + " of " + report.factualCount()
                        + " statements unsupported; asking the model to revise");
                events.status("revising", "Some statements weren't supported by the sources; revising");
                messages.addObject().put("role", "user").put("content", Prompts.revise(listFailing(report)));
                try {
                    LlmClient.LlmResponse r = callWithFailover(messages, toolSpecs, st, events);
                    if (!"tool_use".equals(r.stopReason()) && !r.text().isBlank()) {
                        finalText = r.text();
                        report = grounding.check(finalText, st.sourceTexts());
                    }
                } catch (LlmClient.LlmException e) {
                    st.guardrail("model_unavailable", "revision failed: " + e.getMessage());
                }
            }
            if (report.failureRate() > maxGroundingFailure) {
                finalText = fallbackAnswer(question, st, "answer failed the grounding check");
                usedFallback = true;
            } else {
                finalText = stripUnsupported(finalText, report, st);
            }
            if (!report.invalidCitations().isEmpty()) {
                st.guardrail("invalid_citation_removed", report.invalidCitations().toString());
                for (Integer n : report.invalidCitations()) finalText = finalText.replace("[" + n + "]", "");
            }
        }

        PiiRedactor.Result red = redactor.redact(finalText);
        if (red.changed()) st.guardrail("sensitive_data_redacted", String.join(", ", red.redactedTypes()));
        finalText = red.text();

        // ---------------- result, cost, audit ----------------
        List<AgentModels.Citation> citations = citations(finalText, st);
        String outcome = outcome(finalText, citations, usedFallback, st);
        List<AgentModels.Usage> usage = new ArrayList<>();
        double cost = 0;
        Map<String, Usage> byModel = new LinkedHashMap<>();
        for (Usage u : st.usages) byModel.merge(u.model(), u, Usage::plus);
        int inTok = 0, outTok = 0;
        for (Usage u : byModel.values()) {
            double c = costs.cost(u);
            cost += c;
            inTok += u.inputTokens() + u.cacheReadTokens() + u.cacheWriteTokens();
            outTok += u.outputTokens();
            usage.add(new AgentModels.Usage(u.model(), u.inputTokens(), u.outputTokens(), u.cacheReadTokens(), c));
        }
        long latency = System.currentTimeMillis() - start;

        String auditId = null;
        try {
            auditId = audit.record(new AuditService.Entry(user.tenantId(), user.userId(), question, finalText,
                    List.copyOf(st.retrievedDocs), citations.stream().map(AgentModels.Citation::docId).distinct().toList(),
                    List.copyOf(st.dropped), st.guardrails, st.trace, llm.model(), inTok, outTok, cost, latency, outcome))
                    .toString();
        } catch (RuntimeException e) {
            log.error("audit write failed", e); // never lose the answer because the audit insert failed; alert on this
        }

        meters.counter("docassist.answers", "outcome", outcome).increment();
        meters.summary("docassist.answer.cost_usd").record(cost);
        meters.timer("docassist.answer.latency").record(java.time.Duration.ofMillis(latency));

        return new AgentModels.Result(finalText, outcome, citations, st.trace, st.guardrails, List.copyOf(st.dropped),
                st.passagesConsidered, usage, cost, latency, auditId, st.jobId);
    }

    /** Primary model first; on a retryable provider error, one attempt on the fallback model. */
    private LlmClient.LlmResponse callWithFailover(ArrayNode messages, List<LlmClient.ToolSpec> toolSpecs,
                                                   TurnState st, AgentModels.Events events) {
        try {
            LlmClient.LlmResponse r = llm.create(new LlmClient.LlmRequest(llm.model(), Prompts.SYSTEM, messages, toolSpecs, 0));
            st.usages.add(r.usage());
            return r;
        } catch (LlmClient.LlmException e) {
            if (!e.retryable() || llm.model().equals(llm.fastModel())) throw e;
            st.guardrail("model_failover", llm.model() + " unavailable (" + e.status() + "), using " + llm.fastModel());
            events.status("failover", "Primary model is busy; continuing on the fallback model");
            LlmClient.LlmResponse r = llm.create(new LlmClient.LlmRequest(llm.fastModel(), Prompts.SYSTEM, messages, toolSpecs, 0));
            st.usages.add(r.usage());
            return r;
        }
    }

    private String fallbackAnswer(String question, TurnState st, String reason) {
        if (st.sources.isEmpty() && "model unavailable".equals(reason)) return UNAVAILABLE;
        st.guardrail("extractive_fallback", reason);
        ExtractiveAnswerer.Answer a = extractive.answer(question, st.asBlocks());
        if (!a.found()) return ExtractiveAnswerer.NOT_FOUND;
        return "Here is what the documents say directly (" + reason + "):\n\n" + a.text();
    }

    private String stripUnsupported(String text, GroundingChecker.Report report, TurnState st) {
        int removed = 0;
        String out = text;
        for (GroundingChecker.SentenceCheck s : report.sentences()) {
            if (s.status() == GroundingChecker.Status.UNSUPPORTED) {
                out = out.replace(s.sentence(), "");
                removed++;
            }
        }
        if (removed > 0) {
            st.guardrail("unsupported_statements_removed", removed + " statement(s) removed by the grounding check");
            out = out.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        }
        long uncited = report.sentences().stream().filter(s -> s.status() == GroundingChecker.Status.UNCITED).count();
        if (uncited > 0) st.guardrail("uncited_statements", uncited + " factual statement(s) without a citation");
        return out;
    }

    private static String listFailing(GroundingChecker.Report report) {
        StringBuilder sb = new StringBuilder();
        for (GroundingChecker.SentenceCheck s : report.sentences()) {
            if (s.status() == GroundingChecker.Status.UNSUPPORTED || s.status() == GroundingChecker.Status.UNCITED) {
                sb.append("- ").append(s.sentence()).append(" (").append(s.status().name().toLowerCase()).append(")\n");
            }
        }
        return sb.toString();
    }

    private List<AgentModels.Citation> citations(String text, TurnState st) {
        Set<Integer> used = new LinkedHashSet<>();
        Matcher m = CITE.matcher(text);
        while (m.find()) used.add(Integer.parseInt(m.group(1)));
        List<AgentModels.Citation> out = new ArrayList<>();
        for (int n : used) {
            TurnState.Registered r = st.byNumber(n);
            if (r == null) continue;
            RetrievedChunk c = r.chunk();
            String snippet = c.body().length() > 600 ? c.body().substring(0, 600) + "…" : c.body();
            out.add(new AgentModels.Citation(n, c.docId(), c.title(), c.url(), c.sectionPath(),
                    c.modifiedAt() == null ? null : c.modifiedAt().toString().substring(0, 10),
                    c.status(), c.sourceSystem(), redactor.redact(snippet).text()));
        }
        return out;
    }

    static final String UNAVAILABLE =
            "The assistant is temporarily unavailable, and no documents were retrieved yet. Please try again in a minute.";

    private static String outcome(String text, List<AgentModels.Citation> citations, boolean fallback, TurnState st) {
        if (text.equals(UNAVAILABLE)) return "error";
        if (st.jobId != null) return "answered";
        boolean notFound = text.startsWith(ExtractiveAnswerer.NOT_FOUND)
                || (citations.isEmpty() && text.toLowerCase().contains("couldn't find"));
        if (notFound) return "not_found";
        return fallback ? "partial" : "answered";
    }

    /** Prior turns are replayed as plain text (no sources), alternating user/assistant, most recent N only. */
    private void appendHistory(ArrayNode messages, List<AgentModels.Turn> history) {
        if (history == null || history.isEmpty()) return;
        List<AgentModels.Turn> recent = history.subList(Math.max(0, history.size() - historyTurns * 2), history.size());
        String expected = "user";
        for (AgentModels.Turn t : recent) {
            if (!expected.equals(t.role()) || t.content() == null || t.content().isBlank()) continue;
            String content = t.content().length() > 4000 ? t.content().substring(0, 4000) : t.content();
            messages.addObject().put("role", t.role()).put("content", content);
            expected = expected.equals("user") ? "assistant" : "user";
        }
        if (messages.size() > 0 && "user".equals(messages.get(messages.size() - 1).path("role").asText())) {
            messages.remove(messages.size() - 1); // keep strict alternation before the new user turn
        }
    }
}
