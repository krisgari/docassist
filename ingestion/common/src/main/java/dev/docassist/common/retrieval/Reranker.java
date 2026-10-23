package dev.docassist.common.retrieval;

import java.util.List;

/** Second-stage ranking over fused candidates. Swap in a cross-encoder (Voyage / Cohere rerank) in production. */
public interface Reranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);
}
