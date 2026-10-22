package dev.docassist.api.agent;

import dev.docassist.common.llm.SourceBlocks;
import dev.docassist.common.llm.Usage;
import dev.docassist.common.retrieval.RetrievedChunk;
import dev.docassist.common.security.Principals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything one agent turn accumulates: numbered sources, trace, guardrail events, usage. */
final class TurnState {

    record Registered(int n, RetrievedChunk chunk) {}

    final Principals principals;
    final Map<String, Registered> sources = new LinkedHashMap<>();
    final List<AgentModels.ToolTrace> trace = new ArrayList<>();
    final List<AgentModels.GuardrailEvent> guardrails = new ArrayList<>();
    final Set<String> dropped = new LinkedHashSet<>();
    final Set<String> retrievedDocs = new LinkedHashSet<>();
    final List<Usage> usages = new ArrayList<>();
    int passagesConsidered = 0;
    int contextTokens = 0;
    int toolCalls = 0;
    String jobId;

    TurnState(Principals principals) {
        this.principals = principals;
    }

    /** Stable numbering: the same section keeps its number for the whole turn, across searches. */
    Registered register(RetrievedChunk c) {
        String key = c.docId() + "#" + c.parentOrdinal();
        Registered existing = sources.get(key);
        if (existing != null) return existing;
        Registered r = new Registered(sources.size() + 1, c);
        sources.put(key, r);
        return r;
    }

    Map<Integer, String> sourceTexts() {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Registered r : sources.values()) out.put(r.n(), r.chunk().sectionPath() + "\n" + r.chunk().body());
        return out;
    }

    Registered byNumber(int n) {
        for (Registered r : sources.values()) if (r.n() == n) return r;
        return null;
    }

    List<SourceBlocks.Source> asBlocks() {
        List<SourceBlocks.Source> out = new ArrayList<>();
        for (Registered r : sources.values()) out.add(TurnState.block(r));
        return out;
    }

    static SourceBlocks.Source block(Registered r) {
        RetrievedChunk c = r.chunk();
        String modified = c.modifiedAt() == null ? "" : c.modifiedAt().toString().substring(0, 10);
        return new SourceBlocks.Source(r.n(), c.docId(), c.title(), c.sectionPath(), modified, c.status(),
                c.injectionFlag(), c.body());
    }

    void guardrail(String type, String detail) {
        guardrails.add(new AgentModels.GuardrailEvent(type, detail));
    }
}
