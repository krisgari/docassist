package dev.docassist.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.docassist.api.jobs.JobService;
import dev.docassist.common.config.DocassistProperties;
import dev.docassist.common.llm.LlmClient;
import dev.docassist.common.llm.SourceBlocks;
import dev.docassist.common.retrieval.RetrievalService;
import dev.docassist.common.retrieval.RetrievedChunk;
import dev.docassist.common.security.PermissionService;
import dev.docassist.common.security.Principals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The agent's tools. Each one is capability-scoped (read-only, runs as the current user) and returns either
 * numbered source blocks or a structured error the model can act on. No tool can write to a source system or
 * reach an arbitrary URL.
 */
@Component
public class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(AgentTools.class);

    record Outcome(String content, boolean error) {}

    private final RetrievalService retrieval;
    private final PermissionService permissions;
    private final JobService jobs;
    private final ObjectMapper mapper;
    private final int maxContextTokens;

    public AgentTools(RetrievalService retrieval, PermissionService permissions, JobService jobs, ObjectMapper mapper,
                      DocassistProperties props) {
        this.retrieval = retrieval;
        this.permissions = permissions;
        this.jobs = jobs;
        this.mapper = mapper;
        this.maxContextTokens = props.getRetrieval().getMaxContextTokens();
    }

    List<LlmClient.ToolSpec> specs() {
        return List.of(
                new LlmClient.ToolSpec("search_docs",
                        "Hybrid search over the company documents the current user can access. Returns numbered "
                                + "passages with title, section, last-modified date and status. Use focused queries; "
                                + "include exact identifiers (ticket ids, product names, policy names) when you have them.",
                        schema("""
                                {"type":"object","properties":{
                                  "query":{"type":"string","description":"Search query"},
                                  "doc_type":{"type":"string","enum":["policy","faq","runbook","reference","process","report","playbook","notes"],"description":"Optional document type filter"},
                                  "modified_after":{"type":"string","description":"Optional YYYY-MM-DD; only documents modified on or after this date"},
                                  "include_superseded":{"type":"boolean","description":"Include documents marked superseded (default true; they are ranked lower)"}
                                },"required":["query"]}""")),
                new LlmClient.ToolSpec("fetch_doc",
                        "Fetch the full current text of one document by its doc id (from a search result), "
                                + "permission-checked for the current user.",
                        schema("""
                                {"type":"object","properties":{"doc_id":{"type":"string"}},"required":["doc_id"]}""")),
                new LlmClient.ToolSpec("start_summary_job",
                        "Start a background job that reads every relevant document the user can access and writes "
                                + "a cited briefing on a topic. Use for 'summarize everything about X' requests. "
                                + "Returns immediately with a job id; the user sees progress in their Jobs panel.",
                        schema("""
                                {"type":"object","properties":{"topic":{"type":"string"}},"required":["topic"]}""")));
    }

    Outcome execute(String name, JsonNode input, TurnState st, AgentModels.Events events) {
        long start = System.currentTimeMillis();
        Map<String, Object> traceInput = mapper.convertValue(input, Map.class);
        try {
            Outcome out;
            String summary;
            switch (name) {
                case "search_docs" -> {
                    String query = required(input, "query");
                    events.status("searching", "Searching for \"" + query + "\"");
                    SearchSummary s = search(query, input, st);
                    out = s.outcome;
                    summary = s.summary;
                }
                case "fetch_doc" -> {
                    String docId = required(input, "doc_id");
                    events.status("reading", "Reading " + docId);
                    out = fetch(docId, st);
                    summary = out.error() ? "not available" : "loaded " + docId;
                }
                case "start_summary_job" -> {
                    String topic = required(input, "topic");
                    JobService.JobView job = startSummaryJob(st.principals, topic);
                    st.jobId = job.jobId();
                    events.job(job);
                    ObjectNode r = mapper.createObjectNode();
                    r.put("job_id", job.jobId());
                    r.put("status", job.status());
                    r.put("reused_existing_job", job.reused());
                    r.put("note", "Running in the background. Tell the user it will appear in their Jobs panel.");
                    out = new Outcome(r.toString(), false);
                    summary = (job.reused() ? "reused job " : "queued job ") + job.jobId().substring(0, 8);
                }
                default -> {
                    out = error("unknown_tool", false, "No tool named " + name);
                    summary = "unknown tool";
                }
            }
            record(st, events, name, traceInput, summary, start, out.error());
            return out;
        } catch (IllegalArgumentException e) {
            record(st, events, name, traceInput, e.getMessage(), start, true);
            return error("invalid_arguments", false, e.getMessage());
        } catch (RuntimeException e) {
            // Infrastructure failure (DB, cache, Kafka). Give the model a structured, secret-free error.
            log.warn("tool {} failed", name, e);
            record(st, events, name, traceInput, "failed: " + e.getClass().getSimpleName(), start, true);
            return error("tool_unavailable", true, "The " + name + " tool is temporarily unavailable.");
        }
    }

    private record SearchSummary(Outcome outcome, String summary) {}

    /** Structured search result shared by the in-process loop and the LangGraph agent's internal API. */
    public record Passages(List<RetrievedChunk> passages, int candidates, boolean cacheHit, long millis,
                           List<String> deniedDocIds) {}

    /** Hybrid search, live permission check, then small-to-big expansion. No numbering, no budget: the caller owns those. */
    public Passages searchPassages(Principals principals, String query, JsonNode input) {
        RetrievalService.Filters filters = new RetrievalService.Filters(
                text(input, "doc_type"), null, date(input, "modified_after"),
                !input.has("include_superseded") || input.path("include_superseded").asBoolean(true));
        RetrievalService.Result r = retrieval.search(principals, query, filters);
        Set<String> docIds = new LinkedHashSet<>();
        for (RetrievedChunk c : r.chunks()) docIds.add(c.docId());
        PermissionService.LateCheckResult check = permissions.lateCheck(principals, docIds);
        List<RetrievedChunk> allowed = r.chunks().stream().filter(c -> check.allowedDocIds().contains(c.docId())).toList();
        List<RetrievedChunk> expanded = retrieval.expandToSections(principals.tenantId(), allowed, 700);
        List<RetrievedChunk> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RetrievedChunk c : expanded) if (seen.add(c.docId() + "#" + c.parentOrdinal())) unique.add(c);
        return new Passages(unique, r.candidates(), r.cacheHit(), r.millis(), check.deniedDocIds());
    }

    /** Whole document by section, permission-checked. Empty when missing or not accessible (indistinguishable on purpose). */
    public List<RetrievedChunk> fetchPassages(Principals principals, String docId) {
        PermissionService.LateCheckResult check = permissions.lateCheck(principals, List.of(docId));
        if (check.allowedDocIds().isEmpty()) return List.of();
        List<RetrievedChunk> chunks = retrieval.documentChunks(principals.tenantId(), principals.all().toArray(new String[0]), docId);
        Map<Integer, StringBuilder> sections = new LinkedHashMap<>();
        Map<Integer, RetrievedChunk> firsts = new LinkedHashMap<>();
        for (RetrievedChunk c : chunks) {
            sections.computeIfAbsent(c.parentOrdinal(), k -> new StringBuilder()).append(c.body()).append("\n\n");
            firsts.putIfAbsent(c.parentOrdinal(), c);
        }
        List<RetrievedChunk> out = new ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> e : sections.entrySet()) {
            out.add(firsts.get(e.getKey()).withBody(e.getValue().toString().strip()));
        }
        return out;
    }

    public JobService.JobView startSummaryJob(Principals principals, String topic) {
        return jobs.submit(principals.tenantId(), principals.userId(), "summarize_documents", Map.of("topic", topic));
    }

    private SearchSummary search(String query, JsonNode input, TurnState st) {
        Passages r = searchPassages(st.principals, query, input);
        st.passagesConsidered += r.candidates();
        if (!r.deniedDocIds().isEmpty()) {
            st.dropped.addAll(r.deniedDocIds());
            st.guardrail("acl_late_check_drop", r.deniedDocIds().size() + " document(s) removed by live permission check");
        }
        List<RetrievedChunk> expanded = r.passages();

        List<SourceBlocks.Source> blocks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RetrievedChunk c : expanded) {
            if (!seen.add(c.docId() + "#" + c.parentOrdinal())) continue;
            int tokens = c.body().length() / 4;
            if (st.contextTokens + tokens > maxContextTokens) {
                st.guardrail("context_budget", "Stopped adding passages at " + st.contextTokens + " tokens");
                break;
            }
            st.contextTokens += tokens;
            TurnState.Registered reg = st.register(c);
            st.retrievedDocs.add(c.docId());
            if (c.injectionFlag()) st.guardrail("prompt_injection_flagged", c.docId() + " · " + c.sectionPath());
            blocks.add(TurnState.block(reg));
        }
        if (blocks.isEmpty()) {
            return new SearchSummary(new Outcome("No passages matched. Try different terms, or tell the user nothing was found.", false),
                    "no results");
        }
        String summary = blocks.size() + " passages from " + blocks.stream().map(SourceBlocks.Source::docId).distinct().count()
                + " documents" + (r.cacheHit() ? " (cache hit)" : "") + ", " + r.millis() + " ms";
        return new SearchSummary(new Outcome(SourceBlocks.format(blocks), false), summary);
    }

    private Outcome fetch(String docId, TurnState st) {
        List<RetrievedChunk> sections = fetchPassages(st.principals, docId);
        // Same response for "doesn't exist" and "no access": never confirm a document exists to someone who can't see it.
        if (sections.isEmpty()) return error("not_found", false, "No accessible document with that id.");
        List<SourceBlocks.Source> blocks = new ArrayList<>();
        for (RetrievedChunk c : sections) {
            int tokens = c.body().length() / 4;
            if (st.contextTokens + tokens > maxContextTokens) {
                st.guardrail("context_budget", "Document truncated at " + st.contextTokens + " tokens");
                break;
            }
            st.contextTokens += tokens;
            if (c.injectionFlag()) st.guardrail("prompt_injection_flagged", c.docId() + " · " + c.sectionPath());
            blocks.add(TurnState.block(st.register(c)));
            st.retrievedDocs.add(docId);
        }
        return new Outcome(SourceBlocks.format(blocks), false);
    }

    private Outcome error(String type, boolean retryable, String message) {
        ObjectNode e = mapper.createObjectNode();
        e.put("error_type", type);
        e.put("retryable", retryable);
        e.put("message", message);
        return new Outcome(e.toString(), true);
    }

    private void record(TurnState st, AgentModels.Events events, String name, Map<String, Object> input,
                        String summary, long start, boolean error) {
        AgentModels.ToolTrace t = new AgentModels.ToolTrace(name, input, summary, System.currentTimeMillis() - start, error);
        st.trace.add(t);
        events.tool(t);
    }

    private JsonNode schema(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String required(JsonNode input, String field) {
        String v = text(input, field);
        if (v == null) throw new IllegalArgumentException("missing required argument '" + field + "'");
        if (v.length() > 500) throw new IllegalArgumentException("'" + field + "' is too long");
        return v;
    }

    static String text(JsonNode input, String field) {
        JsonNode n = input.path(field);
        return n.isTextual() && !n.asText().isBlank() ? n.asText().trim() : null;
    }

    private static java.time.Instant date(JsonNode input, String field) {
        String v = text(input, field);
        if (v == null) return null;
        try {
            return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            throw new IllegalArgumentException("'" + field + "' must be YYYY-MM-DD");
        }
    }
}
