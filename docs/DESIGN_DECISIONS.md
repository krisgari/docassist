# Design decisions

Short records of the choices that shape the system, what was traded off, and when to revisit each one.

| # | Decision | Why | Trade-off | Revisit when |
| --- | --- | --- | --- | --- |
| 1 | Enforce ACLs inside the search query, plus a live late check | Post-filtering starves top-k and leaks existence through counts; the index can lag the source | Two permission paths to maintain | The source offers a fast batch-permission API you can call for every result |
| 2 | Denormalize ACL principals onto every chunk | One query does ACL + vector + text; GIN index on the array | An ACL change rewrites all of a document's chunks | Very large documents with frequent ACL churn: move to a doc-level join |
| 3 | Separate `acl.events` topic | Revocations must not wait behind bulk re-indexing | Two consumers touch the same rows (ordered per doc by key) | Never; this is the safety property |
| 4 | Structure-aware chunking with a context header and parent pointer | Biggest single retrieval-quality lever on internal docs; tables stay intact | Uneven chunk sizes | Corpus is mostly unstructured text (chat logs): add semantic splitting |
| 5 | Hybrid search with RRF before reranking | Internal corpora are full of identifiers that embeddings miss | Two queries per search | Never removed; rerank model may change |
| 6 | Cache ids, not text, keyed by ACL fingerprint and index version | No content crosses users; any write invalidates instantly | Cache hit still loads rows | Load shows the row fetch matters |
| 7 | No semantic answer cache for permissioned data | A near-match answer could be built from docs the second user can't see | Repeated questions cost full price | Public-only corpora: enable per tenant |
| 8 | Sync tools under ~3 s, everything slower as a Kafka job | Keeps chat latency predictable; jobs survive restarts and retry | More moving parts | A workflow engine (Temporal) is adopted for multi-step jobs |
| 9 | Grounding check, one revision, then extractive fallback | A wrong confident answer costs more than a quoted partial one | Extra model call on failures (rare) | An NLI or small-model verifier is cheaper and better |
| 10 | Mock model that speaks the same tool protocol | Offline demos, deterministic CI, a baseline the real model must beat | Mock quality is limited (it misses paraphrases) | Never; it's the CI backbone |
| 11 | LangGraph orchestrates; Java enforces | The graph (plan, tools, verify, revise, fallback) is explicit and testable, and LangGraph is what many teams standardise on; permissions, the grounding check, redaction and audit stay in one tested service whichever orchestrator runs | One extra service and network hop per tool call (~ms inside the cluster) | Tool calls dominate latency: co-locate, or move retrieval behind a gRPC API |
| 12 | Store answer hashes, not answers, in the audit log | Audit without duplicating sensitive content | Can't replay an answer from the log alone | The customer's retention policy requires full transcripts |
| 13 | Agent service holds no authority | It forwards the user's JWT on every call and has no database or index credentials, so a prompt-injected agent still cannot read beyond the user's access | The agent can't cache across users | Never; this is the safety property |
| 14 | Plain-HTTP Claude client in Java (kept for the Java loop and job worker) | Every header, retry and cache-control choice is visible | Re-implementing a little of the SDK | The official Java SDK covers your needs |
