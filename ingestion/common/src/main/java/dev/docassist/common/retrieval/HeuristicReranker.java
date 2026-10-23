package dev.docassist.common.retrieval;

import dev.docassist.common.util.Text;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dependency-free reranker: fused rank + query-term coverage + small recency boost, with penalties for
 * superseded documents and chunks flagged for prompt injection. Weights are tuned against the golden set,
 * not by hand-picking examples; replace with a cross-encoder once a provider key is available.
 */
public final class HeuristicReranker implements Reranker {

    private final Instant now;

    public HeuristicReranker() {
        this(null);
    }

    HeuristicReranker(Instant fixedNow) {
        this.now = fixedNow;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        Set<String> qTerms = new LinkedHashSet<>(Text.terms(query));
        double maxFused = candidates.stream().mapToDouble(RetrievedChunk::fusedScore).max().orElse(1.0);
        Instant reference = now != null ? now : Instant.now();

        List<RetrievedChunk> scored = new ArrayList<>();
        for (RetrievedChunk c : candidates) {
            Set<String> cTerms = new HashSet<>(Text.terms(c.sectionPath() + " " + c.body()));
            double coverage = 0;
            if (!qTerms.isEmpty()) {
                int hit = 0;
                for (String t : qTerms) if (cTerms.contains(t)) hit++;
                coverage = (double) hit / qTerms.size();
            }
            double fusedNorm = maxFused > 0 ? c.fusedScore() / maxFused : 0;
            double ageDays = c.modifiedAt() == null ? 3650 : Duration.between(c.modifiedAt(), reference).toDays();
            double recency = 0.05 * Math.exp(-Math.max(0, ageDays) / 365.0);

            double score = 0.45 * fusedNorm + 0.50 * coverage + recency;
            if ("superseded".equals(c.status())) score -= 0.30;
            if (c.injectionFlag()) score -= 0.20;
            scored.add(c.withScore(score));
        }
        scored.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return scored.subList(0, Math.min(topK, scored.size()));
    }
}
