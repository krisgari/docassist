package dev.docassist.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.docassist.api.jobs.JobService;
import dev.docassist.api.security.CurrentUser;
import dev.docassist.common.guardrails.GroundingChecker;
import dev.docassist.common.llm.ExtractiveAnswerer;
import dev.docassist.common.llm.Usage;
import dev.docassist.common.retrieval.RetrievedChunk;
import dev.docassist.common.security.PermissionService;
import dev.docassist.common.security.Principals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The capabilities the LangGraph agent service may use. LangGraph decides <em>what</em> to do; this service decides
 * what is <em>allowed</em> and what the user finally sees.
 *
 * <p>Every call carries two credentials: the end user's JWT (forwarded by the gateway, so every permission check
 * runs as that user) and the service token (so only the agent service can reach these endpoints, and a browser
 * holding a user token cannot write audit records or bypass the output policy).</p>
 */
@RestController
@RequestMapping("/internal/agent")
public class InternalAgentController {

    /** A retrieved section as the agent sees it. {@code n} is assigned by the agent; null on tool responses. */
    public record Passage(Integer n, String docId, int parentOrdinal, String title, String url, String sourceSystem,
                          String section, String modified, String status, boolean flagged, String text) {

        static Passage of(RetrievedChunk c) {
            return new Passage(null, c.docId(), c.parentOrdinal(), c.title(), c.url(), c.sourceSystem(),
                    c.sectionPath(), c.modifiedAt() == null ? "" : c.modifiedAt().toString().substring(0, 10),
                    c.status(), c.injectionFlag(), c.body());
        }

        RetrievedChunk toChunk() {
            java.time.Instant modifiedAt = null;
            try {
                if (modified != null && !modified.isBlank()) {
                    modifiedAt = LocalDate.parse(modified).atStartOfDay().toInstant(ZoneOffset.UTC);
                }
            } catch (RuntimeException ignored) {
                // a bad date only affects the date shown on the citation
            }
            return new RetrievedChunk(docId, parentOrdinal, parentOrdinal, title, url, sourceSystem, section, "",
                    text, status, null, flagged, List.of(), modifiedAt, 0, 0, 0, 0);
        }
    }

    public record SearchResponse(List<Passage> passages, int candidates, boolean cacheHit, long millis,
                                 List<String> droppedByPermissionCheck) {}

    public record FetchRequest(String docId) {}

    public record FetchResponse(boolean found, List<Passage> passages) {}

    public record JobRequest(String topic) {}

    public record VerifyRequest(String answer, List<Passage> sources) {}

    public record Failing(String sentence, String status) {}

    public record VerifyResponse(double failureRate, long factualCount, long failingCount, List<Failing> failing,
                                 List<Integer> invalidCitations, double maxFailureRate, String revisionPrompt) {}

    public record ExtractiveRequest(String question, String reason, List<Passage> sources) {}

    public record ExtractiveResponse(boolean found, String text) {}

    public record UsageLine(String model, int inputTokens, int outputTokens, int cacheReadTokens, int cacheWriteTokens) {}

    public record FinalizeRequest(String question, String answer, boolean usedFallback, List<Passage> sources,
                                  List<AgentModels.ToolTrace> trace, List<AgentModels.GuardrailEvent> guardrails,
                                  List<String> dropped, List<UsageLine> usage, int passagesConsidered, long latencyMs,
                                  String jobId, String model) {}

    private final AgentTools tools;
    private final AgentService agent;
    private final PermissionService permissions;
    private final ExtractiveAnswerer extractive;
    private final byte[] serviceToken;

    public InternalAgentController(AgentTools tools, AgentService agent, PermissionService permissions,
                                   ExtractiveAnswerer extractive,
                                   @Value("${docassist.agent.internal-token}") String serviceToken) {
        this.tools = tools;
        this.agent = agent;
        this.permissions = permissions;
        this.extractive = extractive;
        this.serviceToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/search")
    public SearchResponse search(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                 @RequestBody JsonNode input) {
        Principals p = principals(jwt, token);
        String query = AgentTools.required(input, "query");
        AgentTools.Passages r = tools.searchPassages(p, query, input);
        return new SearchResponse(r.passages().stream().map(Passage::of).toList(), r.candidates(), r.cacheHit(),
                r.millis(), r.deniedDocIds());
    }

    @PostMapping("/fetch")
    public FetchResponse fetch(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Internal-Token", required = false) String token,
                               @RequestBody FetchRequest req) {
        Principals p = principals(jwt, token);
        if (req.docId() == null || req.docId().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "docId is required");
        List<RetrievedChunk> sections = tools.fetchPassages(p, req.docId().trim());
        return new FetchResponse(!sections.isEmpty(), sections.stream().map(Passage::of).toList());
    }

    @PostMapping("/jobs")
    public JobService.JobView startJob(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                       @RequestBody JobRequest req) {
        Principals p = principals(jwt, token);
        if (req.topic() == null || req.topic().isBlank() || req.topic().length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topic must be 1-300 characters");
        }
        return tools.startSummaryJob(p, req.topic().trim());
    }

    @PostMapping("/verify")
    public VerifyResponse verify(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                 @RequestBody VerifyRequest req) {
        Principals p = principals(jwt, token);
        TurnState st = rebuild(p, req.sources());
        GroundingChecker.Report report = agent.groundingReport(req.answer() == null ? "" : req.answer(), st);
        List<Failing> failing = report.sentences().stream()
                .filter(s -> s.status() == GroundingChecker.Status.UNSUPPORTED || s.status() == GroundingChecker.Status.UNCITED)
                .map(s -> new Failing(s.sentence(), s.status().name().toLowerCase()))
                .toList();
        return new VerifyResponse(report.failureRate(), report.factualCount(), report.failingCount(), failing,
                report.invalidCitations(), agent.maxGroundingFailure(), Prompts.revise(AgentService.listFailing(report)));
    }

    @PostMapping("/extractive")
    public ExtractiveResponse extractive(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                         @RequestBody ExtractiveRequest req) {
        Principals p = principals(jwt, token);
        TurnState st = rebuild(p, req.sources());
        if (req.reason() == null || req.reason().isBlank()) {
            // Mock-model answer: plain extractive text, no "fallback" framing.
            ExtractiveAnswerer.Answer a = extractive.answer(req.question(), st.asBlocks());
            return new ExtractiveResponse(a.found(), a.text());
        }
        String text = agent.fallbackAnswer(req.question(), st, req.reason());
        return new ExtractiveResponse(!text.equals(ExtractiveAnswerer.NOT_FOUND) && !text.equals(AgentService.UNAVAILABLE), text);
    }

    @PostMapping("/finalize")
    public AgentModels.Result finalizeAnswer(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(value = "X-Internal-Token", required = false) String token,
                                             @RequestBody FinalizeRequest req) {
        Principals p = principals(jwt, token);
        CurrentUser user = CurrentUser.from(jwt);
        List<Passage> sources = req.sources() == null ? List.of() : req.sources();

        // Defense in depth: re-check every cited document against live permissions before anything is shown.
        Set<String> docIds = new LinkedHashSet<>();
        for (Passage s : sources) docIds.add(s.docId());
        PermissionService.LateCheckResult check = permissions.lateCheck(p, docIds);
        List<Passage> allowed = sources.stream().filter(s -> check.allowedDocIds().contains(s.docId())).toList();

        TurnState st = rebuild(p, allowed);
        if (req.trace() != null) st.trace.addAll(req.trace());
        if (req.guardrails() != null) st.guardrails.addAll(req.guardrails());
        if (req.dropped() != null) st.dropped.addAll(req.dropped());
        if (!check.deniedDocIds().isEmpty()) {
            st.dropped.addAll(check.deniedDocIds());
            st.guardrail("acl_late_check_drop", check.deniedDocIds().size() + " document(s) removed at finalize");
        }
        if (req.usage() != null) {
            for (UsageLine u : req.usage()) {
                st.usages.add(new Usage(u.model(), u.inputTokens(), u.outputTokens(), u.cacheReadTokens(), u.cacheWriteTokens()));
            }
        }
        st.passagesConsidered = req.passagesConsidered();
        st.jobId = req.jobId();
        String model = req.model() == null ? "langgraph" : req.model();
        return agent.finalizeAnswer(user, req.question(), req.answer(), req.usedFallback(), st, req.latencyMs(), model);
    }

    /** Rebuilds numbered sources exactly as the agent numbered them (it registers them 1..N in order). */
    private TurnState rebuild(Principals p, List<Passage> sources) {
        TurnState st = new TurnState(p);
        if (sources == null) return st;
        List<Passage> ordered = new ArrayList<>(sources);
        ordered.sort((a, b) -> Integer.compare(a.n() == null ? Integer.MAX_VALUE : a.n(), b.n() == null ? Integer.MAX_VALUE : b.n()));
        Map<Integer, Passage> byN = new LinkedHashMap<>();
        for (Passage s : ordered) if (s.n() != null) byN.putIfAbsent(s.n(), s);
        int max = byN.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int n = 1; n <= max; n++) {
            Passage s = byN.get(n);
            // A gap (a source removed by the permission re-check) keeps later numbers stable with a placeholder.
            RetrievedChunk c = s != null ? s.toChunk()
                    : new RetrievedChunk("__removed__" + n, n, n, "", "", "", "", "", "", "deleted", null, false,
                            List.of(), null, 0, 0, 0, 0);
            TurnState.Registered r = st.register(c);
            if (r.n() != n) throw new IllegalStateException("source numbering mismatch at " + n);
            if (s != null) st.retrievedDocs.add(s.docId());
        }
        st.sources.entrySet().removeIf(e -> e.getKey().startsWith("__removed__"));
        return st;
    }

    private Principals principals(Jwt jwt, String token) {
        if (token == null || !MessageDigest.isEqual(serviceToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "service token required");
        }
        CurrentUser u = CurrentUser.from(jwt);
        return permissions.principalsFor(u.tenantId(), u.userId());
    }
}
