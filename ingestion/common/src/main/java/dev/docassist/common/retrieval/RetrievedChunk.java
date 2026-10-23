package dev.docassist.common.retrieval;

import java.time.Instant;
import java.util.List;

/** A chunk returned by hybrid search, carrying what reranking, permission checks and citations need. */
public record RetrievedChunk(
        String docId,
        int ordinal,
        int parentOrdinal,
        String title,
        String url,
        String sourceSystem,
        String sectionPath,
        String contextHeader,
        String body,
        String status,
        String sensitivity,
        boolean injectionFlag,
        List<String> aclPrincipals,
        Instant modifiedAt,
        double denseScore,
        double lexicalScore,
        double fusedScore,
        double score) {

    public String key() {
        return docId + "#" + ordinal;
    }

    public RetrievedChunk withFused(double fused) {
        return new RetrievedChunk(docId, ordinal, parentOrdinal, title, url, sourceSystem, sectionPath,
                contextHeader, body, status, sensitivity, injectionFlag, aclPrincipals, modifiedAt,
                denseScore, lexicalScore, fused, score);
    }

    public RetrievedChunk withScore(double s) {
        return new RetrievedChunk(docId, ordinal, parentOrdinal, title, url, sourceSystem, sectionPath,
                contextHeader, body, status, sensitivity, injectionFlag, aclPrincipals, modifiedAt,
                denseScore, lexicalScore, fusedScore, s);
    }

    public RetrievedChunk withBody(String newBody) {
        return new RetrievedChunk(docId, ordinal, parentOrdinal, title, url, sourceSystem, sectionPath,
                contextHeader, newBody, status, sensitivity, injectionFlag, aclPrincipals, modifiedAt,
                denseScore, lexicalScore, fusedScore, score);
    }
}
