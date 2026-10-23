# Demo script (10 minutes)

A walkthrough for an FDE interview or a customer meeting. Each step names the customer problem first, then shows how the system handles it.

## 0. Setup (before the call)

`docker compose up --build`, open http://localhost:3000, and keep `docker compose logs -f worker` in a second window. Optionally run `make tools` for the Kafka UI.

## 1. A grounded answer (1 min)

As **Alice**, ask "How many weeks of parental leave does a primary caregiver get?"

- The answer cites the 2026 policy. Click a citation number to see the exact passage, the source system and the last-updated date.
- The superseded 2023 FAQ is retrieved but ranked lower and labelled *Superseded*. Conflicting versions are the norm in real corpora, and the assistant prefers the current one.
- Open **How this was answered** to show the searches, passages ranked, safeguards, tokens and cost for this one answer.

## 2. Permissions (2 min) — the most important minute

Ask "What is the salary range for a Staff Engineer?" as **Alice**: not found. Switch to **Carol** (HR) and ask the same question: answered with the band.

What to say:
- The ACL filter is applied inside the vector and keyword queries. A post-filter leaks through counts and starves top-k.
- A second, live permission check runs right before text reaches the model, because the index can lag the source.
- "Not found" is identical whether the document doesn't exist or the user can't open it, so the assistant never confirms that a document exists.

Then show **Erin** (contractor): she sees only public docs, so even parental leave is not found.

## 3. Revocation in seconds (1 min)

Edit `corpus/hr/compensation-bands-2026.md` and remove `group:hr` from `acl:`. Within about 5 seconds the worker logs `acl updated ... (N ms after change)`. Ask as Carol again: not found.

What to say: ACL changes ride their own Kafka topic, so a revocation never waits behind a bulk re-index. The propagation time is a metric (`docassist_acl_propagation`). Restore the line afterwards.

## 4. Prompt injection (1 min)

As **Erin**, ask "What is the guest Wi-Fi network called?" The source page also contains "Ignore all previous instructions… admin mode…".

- The answer gives the Wi-Fi name, and the trace shows *Instructions found inside a document were treated as data*.
- What to say: injection is handled in layers. Document text is delimited as data, scanners flag and down-rank it, tools are read-only and run as the user, and redaction runs on output. Even a missed pattern can't widen access, because permissions are enforced in code, not by the model.

## 5. Secrets in documents (30 s)

As **Alice**, ask "What was the example staging key in the API key rotation page?" The key and secret come back as `[REDACTED aws_access_key]` and `[REDACTED aws_secret_key]`.

## 6. Slow work goes async (1.5 min)

Type "parental leave" under **Background jobs**, or ask in chat "Summarize everything about parental leave".

- The job is queued on Kafka, and progress streams back over SSE. The chat stays responsive.
- What to say: jobs are idempotent (the same request on the same corpus version reuses the job). They checkpoint per document, so a crash resumes instead of restarting. Permissions are re-checked when the job runs, not just when it was queued.

## 7. Operating it (2 min)

Switch to **Dave**, then **Operations**.

- Median and 95th-percentile latency, average cost, not-found rate, feedback, safeguards fired, and index freshness, including the flagged wiki page.
- Show a thumbs-down flowing in: every flagged answer is triaged from its trace into one bucket (retrieval miss, ranking miss, generation error, stale source, or permissions gap) and becomes a regression case in `evals/golden.jsonl`.
- Run `make evals` live if there's time: 28 questions, with gates on citation hit, correctness, abstention, and zero leaks.

## 8. The orchestration (1 min, if they ask how the agent works)

Open `agent/app/graph.py`. The docstring diagram is the whole agent: agent → tools loop, then verify, with revise and fallback branches. Point out:
- LangGraph decides the next step; the Java API decides what's allowed. The agent forwards the user's token and has no database access, so even a fully prompt-injected agent can only see what that user can see.
- Budgets (model calls, tool calls, wall clock) are checked in the agent node; LangGraph's recursion limit is the backstop.
- `DOCASSIST_ORCHESTRATOR=java` switches to the hand-written Java loop with the same tools and output policy. Run `make evals` in both modes to compare.

## Likely questions and short answers

- **Why LangGraph and Java together?** Each does what it's good at. LangGraph gives an explicit, testable state machine for the agent and the Python AI ecosystem. Java and Spring hold the enterprise concerns: identity, permissions, transactions, Kafka, audit.

- **Why Kafka, not just a queue?** Ordered per-document partitions (an edit can't overtake a delete), replay for re-indexing, independent consumer groups (ingest, ACL sync, jobs, SSE fan-out), and retry and dead-letter topics built into Spring Kafka.
- **Why Postgres + pgvector rather than a vector DB?** One transactional store for documents, chunks, ACLs, jobs and audit. Hybrid search and filters run in one query. HNSW with iterative scans avoids filter starvation. Move to OpenSearch or Vespa past tens of millions of chunks.
- **How do you know it's right?** Citations on every claim, a grounding check before the answer is shown, a golden set in CI with zero-tolerance leak gates, and online feedback feeding the golden set.
- **What does a question cost?** Shown per answer. A single-search question is a few cents at list prices, and most of the cost is context tokens. The levers are fewer, better passages after reranking, prompt caching on the static prefix, and routing simple questions to the fast model.
- **What happens when Claude is down?** Retries with backoff, then failover to the fallback model, then an extractive answer quoted from the retrieved sources, clearly labelled. The audit log records which path ran.
