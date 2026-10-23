# docassist — a permission-aware document assistant

An agentic assistant that answers employees' questions from internal documents. Every claim cites the passage it came from, and nobody sees content they couldn't open in the source system.

It's built the way many production AI systems are split:

- **Ingestion plane (Spring Boot + Kafka):** connectors, structure-aware chunking, embedding, indexing, permission (ACL) sync and background jobs.
- **Query plane (Python):** a FastAPI service with a **LangGraph** agent driving Claude, permission-checked hybrid search, safeguards, conversation memory in a Postgres **checkpointer**, an **MCP server** for the same tools, and **OpenTelemetry** tracing.

The demo company is a fictional robotics firm, Halcyon Robotics, with 14 documents and 5 employees who have different access. Everything runs offline without an API key: a deterministic mock model drives the same agent graph. Add `ANTHROPIC_API_KEY` to switch to Claude.

## Quick start

```bash
cp .env.example .env            # optional: add ANTHROPIC_API_KEY
docker compose up --build       # first build takes a few minutes
open http://localhost:3000
```

Pick a person on the left and ask a starter question. Then switch to Erin, a contractor, and ask the same thing to watch the answer change. Ask a follow-up and the agent remembers the conversation. Dave is an exec and also sees an **Operations** tab.

| Service | URL |
| --- | --- |
| UI | http://localhost:3000 |
| Query API | http://localhost:8080 (`/health`, OpenAPI docs at `/docs`) |
| MCP server | http://localhost:8080/mcp/ (Streamable HTTP, bearer token required) |
| Worker admin | http://localhost:8090/admin/connector, `POST /admin/sync`, `/actuator/prometheus` |
| Kafka UI | `make tools`, then http://localhost:8081 |
| Jaeger traces | `make observability` + `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318` in `.env`, then http://localhost:16686 |

`make evals` runs the golden set against the running stack. `make test` runs the Java tests and `make query-test` the Python tests.

## Architecture

```
                         INGESTION PLANE (Spring Boot + Kafka)
 corpus/*.md ──► connector ──► Kafka doc.events ──► ingest: parse → chunk → scan → embed → write ─┐
 (stand-in for   (delta scan)   Kafka acl.events ──► ACL fast path (seconds)                       │
  SharePoint)                   Kafka jobs.requests ──► job worker (map/reduce, checkpoints) ──┐   │
                                                                                               │   ▼
                                     shared contract:  Postgres schema · Kafka topics · Redis keys
                                                                                               │   ▲
                         QUERY PLANE (Python: FastAPI + LangGraph)                             │   │
 Browser ──► nginx ──► /api/chat ──► LangGraph agent ──► tools ──► hybrid search, ACL in-query ┼───┘
   ▲  SSE                               │  ▲                └──► start_summary_job ──► jobs.requests
   │                                    │  └── Postgres checkpointer (conversation memory)
   │                                    └──► verify → output policy → audit log
   └──────────── jobs.events (progress) ◄────────────────────────────────────────────────┘
 MCP clients ──► /mcp/ ──► the same tools, as the same user
```

**The contract between the planes** is narrow and tested. The worker's Flyway migrations own the Postgres schema. The Kafka topics are `jobs.requests` (query → ingestion) and `jobs.events` (ingestion → query). In Redis, ingestion bumps the index version on every write, which invalidates the query plane's retrieval cache, and both sides share the group cache. Queries must be embedded exactly as documents were, so `query/tests/test_parity.py` replays outputs recorded from the Java classes. The Python port reproduces Java's embeddings bit for bit, along with its tokenisation, ranking, safeguards and costs, and its answers to all 28 golden-set questions.

**The agent** (`query/app/agent/graph.py`):

```
 START → prepare → agent ─┬─ tool calls ──→ tools ──→ agent           (bounded by call, tool and time budgets)
                          ├─ final draft ─→ verify ─┬─ supported ──────→ finalize → END
                          │                         ├─ unsupported, 1st → revise → agent
                          │                         └─ unsupported, 2nd → fallback → finalize
                          └─ budget hit / model down ────────────────→ fallback → finalize
```

Each conversation is a LangGraph thread in the Postgres checkpointer. The thread id comes from the verified user, so nobody can read another person's thread. At the start of each turn, `prepare` compacts earlier turns. It keeps the questions and the final, policy-approved answers, drops tool calls, tool results, drafts and revision prompts, and limits history to the last N exchanges, so context stays small. The graph decides what happens next. Every tool runs as the user, and every answer passes the output policy (`query/app/policy.py`) before anyone sees it.

## What makes it enterprise-ready

| Concern | How it's handled | Where |
| --- | --- | --- |
| Chunking | Structure-aware: split on headings, never inside a table, list or code block. Oversized tables keep their header. Each chunk has a section path, a context header and a parent pointer for small-to-big expansion. | `ingestion/…/MarkdownChunker` |
| Retrieval quality | Hybrid dense (pgvector HNSW) and lexical (Postgres full text) search, fused with reciprocal rank fusion, then reranked. Superseded docs and injection-flagged chunks are down-ranked. | `query/app/retrieval.py`, `core/ranking.py` |
| Permissions | The ACL filter runs **inside** both search queries, never as a post-filter. A live late check runs before the model sees content and again at finalize; statements built on a source revoked mid-answer are removed. "No access" and "doesn't exist" give the same answer. | `permissions.py`, `tools.py`, `policy.py` |
| Stale data | Content and ACL changes are separate Kafka events. Revocations take a fast path with a propagation metric. Deletes are tombstoned. Unchanged chunks reuse their embeddings. Source dates show on every citation. | `ingestion/…/FilesystemConnector`, `IndexWriter` |
| Caching | Prompt caching on the static prefix. Query embeddings are cached. Retrieval results are cached as ids under an ACL fingerprint plus the index version, so any ingestion write invalidates them instantly. Group membership has a short TTL. | `retrieval.py`, `embeddings.py` |
| Slow tools | Summaries run as Kafka jobs with idempotency keys, per-document checkpoints, retry and dead-letter topics, and an execution-time permission check. Progress streams back over SSE. | `query/app/jobs.py`, `ingestion/…/JobWorker` |
| Orchestration | A LangGraph state machine with explicit nodes and conditional edges, Postgres checkpointer threads, conversation compaction, and custom stream events for live progress. Run-scoped dependencies never enter checkpoints. | `query/app/agent/` |
| Guardrails | Retrieved text is delimited as data, and injection patterns are flagged and down-ranked. Tools are read-only. A grounding check gets one revision, then falls back to quoting the sources. Secrets and PII are redacted. Every turn is rate-limited and audited. | `core/guardrails.py`, `policy.py` |
| Model or tool errors | SDK retries with backoff, then failover to the fallback model. Tools return structured errors the model can act on. Budgets cap model calls, tool calls and wall-clock time, with LangGraph's recursion limit as a backstop. | `agent/graph.py`, `agent/errors.py` |
| Tool reuse (MCP) | `search_docs` and `fetch_doc` are also an MCP server. Each call verifies the user's token and runs as that user, so Claude Desktop, IDEs or another team's agent get exactly the user's access. | `query/app/mcp_server.py` |
| Observability | OpenTelemetry spans for each HTTP request, agent node and MCP call, carrying token, cost and outcome attributes (Jaeger in Compose). LangSmith tracing is optional. The worker exposes Prometheus metrics. The ops view shows recent turns, safeguards and index freshness. | `telemetry.py`, Operations tab |
| Evals | A 28-question golden set covers single-doc, tables, identifiers, conflicting sources, unanswerable questions, permission traps and safety. Gates run in CI, with regression checks against a baseline. Cross-plane parity tests also run in CI. | `evals/`, `query/tests/` |
| Cost | Usage per model is priced with configurable rates, returned with each answer, stored in the audit log and shown in the UI. | `core/costs.py`, `policy.py` |

## Using the MCP server

Get a token (demo login), then point any MCP client at `http://localhost:8080/mcp/` with the header `Authorization: Bearer <token>`:

```bash
curl -s localhost:8080/auth/demo-login -H 'content-type: application/json' -d '{"userId":"alice"}' | jq -r .token
```

The tools are `search_docs(query, doc_type?, modified_after?, include_superseded?)` and `fetch_doc(doc_id)`. Results come back as numbered `<source>` blocks, with injection-flagged passages marked.

## Eval baseline

In mock mode the golden set scores citation hit 0.95, answer correctness 0.95, correct abstention 1.00, permission leaks 0 and safety violations 0. The gates are ≥ 0.85, ≥ 0.80, ≥ 0.80, = 0 and = 0. These numbers come from an offline replica of the request path, and the Python port reproduces its answers exactly. Re-baseline with `make evals` on your stack, and again with a real key. The one mock miss is "What is the minimum password length?". The source says "at least 14 characters", and linking the two phrasings takes semantic understanding, which the Claude path provides.

## Configuration

All settings are environment variables (see `.env.example`).

| Variable | Default | Meaning |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | empty | Empty means the mock model runs the same agent graph offline |
| `DOCASSIST_LLM_MODEL` / `_FAST_MODEL` | `claude-sonnet-5-5` / `claude-haiku-5-5` | Agent model / failover and job map-step model |
| `DOCASSIST_EMBEDDINGS_PROVIDER` | `local` | Must match on both planes: `local` (bit-exact hashing, offline) or `voyage` |
| `DOCASSIST_CHECKPOINTER` | `postgres` | Conversation memory for the agent; `off` makes every question stateless |
| `DOCASSIST_JWT_SECRET` | dev value | Signs demo tokens; replace with your IdP in production |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | empty | Enables tracing (e.g. `http://jaeger:4318`) |
| `DOCASSIST_AGENT_*` | 6 calls, 8 tools, 25 s | Agent budgets |
| `POSTGRES_HOST_PORT`, `REDIS_HOST_PORT` | 5432, 6379 | Change if those ports are taken on your machine |

## Moving from demo to a customer deployment

1. **Identity.** Turn off the demo login. In `query/app/auth.py`, verify the IdP's tokens (Okta or Entra JWKS) instead of HS256. Sync groups through SCIM into `group_member`.
2. **Connectors.** Replace `FilesystemConnector` with SharePoint (Graph delta queries plus webhooks), Google Drive (changes API) or Confluence. The events and everything downstream stay the same.
3. **Late permission check.** Point `late_check` at the source system's permission API, batched and cached for 60 seconds.
4. **Embeddings, reranker and parsing.** Switch both planes to Voyage, add a cross-encoder reranker, and parse PDFs with Docling or Unstructured. Build a shadow index, run the evals, then flip.
5. **Infrastructure.** Managed Kafka, Postgres and Redis, Kubernetes, secrets in a vault, Claude via Bedrock or Vertex for data residency, and retention rules on `audit_log` and the checkpoint tables.

## Project layout

```
ingestion/common    shared Java core + Flyway schema (V1 schema, V2 demo identities) + Kafka contracts
ingestion/worker    connector, ingest consumers (retry + DLT), ACL sync, job worker
query/app           FastAPI app: auth, retrieval, permissions, tools, policy, jobs, MCP, telemetry
query/app/agent     LangGraph graph, state, prompts, backend binding
query/app/core      pure-Python core pinned to the Java classes by parity tests
query/tests         graph, policy and cross-plane parity tests (+ Java-recorded fixtures)
ui/                 React + Vite; nginx proxies /api, /auth and /mcp to the query plane
corpus/             14 synthetic documents with ACL front matter
evals/              golden.jsonl, run_evals.py (stdlib only)
docs/               DEMO_SCRIPT.md, DESIGN_DECISIONS.md
```

## First run checklist

This repo was written in an environment without access to Maven Central, PyPI, npm or Docker. The pure logic on both planes was compiled and tested there. That covers the Java core, the Python core with its parity tests, the agent graph (against a minimal stand-in for LangGraph), the output policy and the eval scorer. The full Spring, Python and React builds run for the first time on your machine. If something fails:

- `docker compose logs worker query` shows the first error. Most first-build problems are dependency versions in the POMs, `query/requirements.txt` or `ui/package.json`.
- If no documents appear, check `curl localhost:8090/admin/connector` for parse errors, then run `POST /admin/sync`.
- If chat returns 401, reload the page; sign-in tokens are per browser session.
- If answers are all "not found", the index is probably empty. In psql, `select count(*) from chunk;` should be about 49.
