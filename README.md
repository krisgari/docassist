# docassist — a permission-aware document assistant

An agentic assistant that answers employees' questions from internal documents. Every claim cites the passage it came from, and nobody sees content they couldn't open in the source system. It was built as an enterprise reference implementation: Spring Boot services, Kafka for ingestion and background jobs, Postgres with pgvector for hybrid search, Redis for caching, Claude for the agent, and a React UI that makes the safeguards visible.

The demo company is a fictional robotics firm, Halcyon Robotics. It has 14 documents and 5 employees with different access. Everything runs offline without an API key: a deterministic mock model drives the same agent loop. Add `ANTHROPIC_API_KEY` to switch to Claude.

## Quick start

```bash
cp .env.example .env            # optional: add ANTHROPIC_API_KEY
docker compose up --build       # first build takes a few minutes
open http://localhost:3000
```

Pick a person on the left and ask a starter question. Then switch to Erin, a contractor, and ask the same thing to watch the answer change. Dave is an exec and also sees an **Operations** tab.

| Service | URL |
| --- | --- |
| UI | http://localhost:3000 |
| Query API | http://localhost:8080 (`/actuator/health`, `/actuator/prometheus`) |
| Worker admin | http://localhost:8090/admin/connector, `POST /admin/sync` |
| Kafka UI | `make tools`, then http://localhost:8081 |

Run the evals against the running stack with `make evals`, and the unit tests with `make test`.

## Architecture

```
 corpus/*.md ──► FilesystemConnector ──► Kafka: doc.events ──► ingest (chunk → scan → embed → write) ──► Postgres + pgvector
 (stand-in for       (delta scan,          acl.events ──────► ACL fast path (≤ seconds)                      ▲   │
  SharePoint/Drive)   every 5 s)                                                                              │   │ hybrid search,
                                                                                                              │   │ ACL filter in-query
 Browser ──► nginx ──► query-api ──► agent loop (Claude tool use) ──► search_docs / fetch_doc ────────────────┘   │
   ▲  SSE                │                    │                  └──► start_summary_job ──► Kafka: jobs.requests ──► job worker
   └─────────────────────┘◄── jobs.events ◄───┼───────────────────────────────────────────────────────────────── (map/reduce,
                                              ▼                                                                    checkpoints)
                         grounding check → redaction → audit log → answer with citations
```

**Worker** (`backend/worker`): connector, Kafka consumers with retry topics and dead-letter topics, the index writer, ACL sync, and async jobs.
**Query API** (`backend/query-api`): JWT auth, the agent loop, tools, guardrails, SSE streaming, jobs, feedback, audit and the ops view.
**Common** (`backend/common`): chunker, embedders, hybrid retrieval, permissions, guardrails, Claude client, mock model, Kafka contracts and the schema.

## What makes it enterprise-ready

| Concern | How it's handled | Where |
| --- | --- | --- |
| Chunking | Structure-aware: split on headings, never inside a table, list or code block. Oversized tables keep their header. Each chunk has a section path and a context header, plus a parent pointer for small-to-big expansion. | `MarkdownChunker` |
| Retrieval quality | Hybrid dense (pgvector HNSW) and lexical (Postgres full text) search, fused with reciprocal rank fusion, then reranked. Superseded docs and injection-flagged chunks are down-ranked. | `RetrievalService`, `HeuristicReranker` |
| Permissions | The ACL filter runs **inside** both search queries, never as a post-filter. A live late check runs right before content reaches the model. The tool runs as the user. "No access" and "doesn't exist" give the same answer. Failures fail closed. | `PermissionService`, `AgentTools` |
| Stale data | Content and ACL changes are separate events. Revocations take a fast path that propagation metrics track. Deletes are tombstoned. Unchanged chunks reuse their embeddings. Source dates show on every citation. | `FilesystemConnector`, `IndexWriter` |
| Caching | Prompt caching on the stable system and tools prefix. Query embeddings are cached. Retrieval results are cached as ids under an ACL fingerprint plus the index version, so a write invalidates them instantly. Group membership has a short TTL. | `CacheKeys`, `CachingEmbedder` |
| Slow tools | Summaries run as Kafka jobs with idempotency keys, per-document checkpoints, retry topics, and an execution-time permission check. Progress streams back over SSE. | `JobWorker`, `JobService`, `JobEventHub` |
| Guardrails | Retrieved text is treated as data, and injection patterns are flagged. Tools are read-only. A grounding check runs with one revision, then falls back to an extractive answer. Secrets and PII are redacted. Every turn is rate-limited and audited. | `guardrails/*`, `AgentService` |
| Model or tool errors | Retries with backoff and jitter honour `retry-after`. Failover goes to the fallback model. Tools return structured errors the model can act on. Hard limits cap model calls, tool calls and wall-clock time. | `AnthropicClient`, `AgentService` |
| Evals | A 28-question golden set covers single-doc, tables, identifiers, conflicting sources, unanswerable questions, permission traps and safety slices. Gates are enforced in CI, with regression checks against a baseline. | `evals/` |
| Cost | Token usage per model is converted with configurable prices. Cost is returned per answer, stored in the audit log, and shown in the UI and the ops view. | `CostCalculator` |
| Observability | Prometheus metrics: retrieval latency, answer latency and cost, outcomes, ACL propagation, dead letters, embeddings reused. The ops view shows recent traces, safeguards fired and index freshness. | `/actuator/prometheus`, Operations tab |

## Eval baseline

These numbers come from an offline replica of the mock-mode request path. It uses the same chunker, ranking, permission filter, answerer, grounding check and redaction classes, but in-memory BM25 instead of Postgres full-text search. Re-baseline with `make evals` on your stack, and again with a real key.

| Metric | Mock baseline | Gate |
| --- | --- | --- |
| Citation hit (expected doc cited) | 0.95 | ≥ 0.85 |
| Answer correct | 0.95 | ≥ 0.80 |
| Correct abstention | 1.00 | ≥ 0.80 |
| Permission leaks | 0 | = 0 |
| Safety violations | 0 | = 0 |

The one mock miss is "What is the minimum password length?". The source says "at least 14 characters", and matching those takes semantic understanding, not term matching. That gap is exactly what the Claude path closes, and it shows why the eval set gates both modes.

## Configuration

All settings are environment variables (see `.env.example`) or `docassist-common.yml`.

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | empty | Empty means the mock model runs the same agent loop offline |
| `DOCASSIST_LLM_MODEL` / `_FAST_MODEL` | `claude-sonnet-5-5` / `claude-haiku-5-5` | Agent and synthesis model / failover and map-step model |
| `DOCASSIST_EMBEDDINGS_PROVIDER` | `local` | `local` (feature hashing, offline) or `voyage` (needs `VOYAGE_API_KEY`) |
| `DOCASSIST_JWT_SECRET` | dev value | Signs demo tokens; replace with your IdP in production |
| `docassist.prices.*` | list prices | USD per million tokens; replace with your contracted rates |
| `docassist.low-information-terms` | `halcyon` | Tenant glossary of words to ignore when matching (the company's own name) |

## Moving from demo to a customer deployment

1. **Identity.** Delete the demo login and set `spring.security.oauth2.resourceserver.jwt.issuer-uri` to the customer's IdP. Sync groups through SCIM into `group_member`.
2. **Connectors.** Replace `FilesystemConnector` with SharePoint (Graph delta queries plus webhooks), Google Drive (changes API) or Confluence. The events and everything downstream stay the same.
3. **Late permission check.** Point `PermissionService.lateCheck` at the source's permission API, batched and cached for 60 seconds.
4. **Embeddings and reranker.** Switch to Voyage and a cross-encoder reranker. Build a shadow index, run evals, then flip.
5. **Infrastructure.** Managed Kafka, Postgres and Redis, secrets in Vault, one Postgres schema or database per tenant for hard isolation, and retention rules on `audit_log`.

## Project layout

```
backend/common      shared core + Flyway schema (V1 schema, V2 demo identities)
backend/worker      connector, ingest consumers, job worker
backend/query-api   auth, agent, tools, chat SSE, jobs, feedback, insights
ui/                 React + Vite; nginx config for same-origin API proxy
corpus/             14 synthetic documents with ACL front matter
evals/              golden.jsonl, run_evals.py (stdlib only)
docs/               DEMO_SCRIPT.md, DESIGN_DECISIONS.md
```

## First run checklist

This repo was written in an environment without access to Maven Central, npm or Docker, so the dependency-free core (chunker, ranking, guardrails, answerer, costs and eval scorer) was compiled and tested there. The UI was rendered against a mock of the API contract, but the full Spring and React builds run for the first time on your machine. If something fails:

- `docker compose logs worker query-api` shows the first error. Most first-build problems are dependency versions in the POMs or `ui/package.json`.
- If no documents appear, check `curl localhost:8090/admin/connector` for parse errors, then `POST /admin/sync`.
- If chat returns 401, sign-in tokens are per session in the UI, so reload the page.
- If answers are all "not found", the index is probably empty. In psql, `select count(*) from chunk;` should be about 49.
