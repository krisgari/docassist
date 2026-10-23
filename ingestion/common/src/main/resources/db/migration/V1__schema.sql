-- docassist schema. Every row carries tenant_id; every query filters on it.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------- identity (synced from the IdP in production; seeded for the demo) ----------
CREATE TABLE app_user (
    tenant_id    TEXT NOT NULL,
    user_id      TEXT NOT NULL,
    email        TEXT NOT NULL,
    display_name TEXT NOT NULL,
    title        TEXT,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE group_member (
    tenant_id TEXT NOT NULL,
    group_id  TEXT NOT NULL,
    user_id   TEXT NOT NULL,
    PRIMARY KEY (tenant_id, group_id, user_id)
);

-- ---------- documents: the index's view of the source system ----------
CREATE TABLE document (
    tenant_id      TEXT        NOT NULL,
    doc_id         TEXT        NOT NULL,          -- stable id from the source system
    source_system  TEXT        NOT NULL,
    title          TEXT        NOT NULL,
    url            TEXT        NOT NULL,
    doc_type       TEXT,
    owner          TEXT,
    sensitivity    TEXT        NOT NULL DEFAULT 'internal',  -- public | internal | confidential | restricted
    status         TEXT        NOT NULL DEFAULT 'active',    -- active | superseded | deleted
    acl_principals TEXT[]      NOT NULL,          -- user:x, group:y, org:public
    source_version BIGINT      NOT NULL,
    content_hash   TEXT        NOT NULL,
    modified_at    TIMESTAMPTZ NOT NULL,
    indexed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, doc_id)
);

-- ---------- chunks: what retrieval actually searches ----------
CREATE TABLE chunk (
    tenant_id       TEXT        NOT NULL,
    doc_id          TEXT        NOT NULL,
    ordinal         INT         NOT NULL,
    parent_ordinal  INT         NOT NULL,          -- first chunk of the enclosing section (small-to-big)
    section_path    TEXT        NOT NULL,
    context_header  TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    token_count     INT         NOT NULL,
    content_hash    TEXT        NOT NULL,
    acl_principals  TEXT[]      NOT NULL,          -- denormalized from document so ACL filters run inside the ANN query
    status          TEXT        NOT NULL DEFAULT 'active',
    modified_at     TIMESTAMPTZ NOT NULL,
    embedding_model TEXT        NOT NULL,
    embedding       vector(512) NOT NULL,
    injection_flag  BOOLEAN     NOT NULL DEFAULT false,
    tsv             tsvector GENERATED ALWAYS AS
                    (setweight(to_tsvector('english', coalesce(section_path, '')), 'A') ||
                     setweight(to_tsvector('english', body), 'B')) STORED,
    PRIMARY KEY (tenant_id, doc_id, ordinal),
    FOREIGN KEY (tenant_id, doc_id) REFERENCES document (tenant_id, doc_id) ON DELETE CASCADE
);

CREATE INDEX chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunk_tsv_gin        ON chunk USING gin (tsv);
CREATE INDEX chunk_acl_gin        ON chunk USING gin (acl_principals);
CREATE INDEX chunk_tenant_status  ON chunk (tenant_id, status);

-- ---------- async jobs ----------
CREATE TABLE job (
    job_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    job_type        TEXT        NOT NULL,
    args            JSONB       NOT NULL,
    idempotency_key TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'queued',   -- queued | running | succeeded | failed
    progress        INT         NOT NULL DEFAULT 0,
    progress_note   TEXT,
    result          JSONB,
    error           TEXT,
    cost_usd        NUMERIC(10, 5) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);

-- ---------- audit + feedback ----------
CREATE TABLE audit_log (
    audit_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         TEXT        NOT NULL,
    user_id           TEXT        NOT NULL,
    question          TEXT        NOT NULL,
    answer_hash       TEXT,
    retrieved_doc_ids TEXT[]      NOT NULL DEFAULT '{}',
    cited_doc_ids     TEXT[]      NOT NULL DEFAULT '{}',
    dropped_by_acl    TEXT[]      NOT NULL DEFAULT '{}',
    guardrail_events  JSONB       NOT NULL DEFAULT '[]',
    tool_calls        JSONB       NOT NULL DEFAULT '[]',
    model             TEXT,
    input_tokens      INT         NOT NULL DEFAULT 0,
    output_tokens     INT         NOT NULL DEFAULT 0,
    cost_usd          NUMERIC(10, 5) NOT NULL DEFAULT 0,
    latency_ms        INT         NOT NULL DEFAULT 0,
    outcome           TEXT        NOT NULL,      -- answered | not_found | partial | error
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_tenant_time ON audit_log (tenant_id, created_at DESC);

CREATE TABLE feedback (
    feedback_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   TEXT        NOT NULL,
    audit_id    UUID        NOT NULL REFERENCES audit_log (audit_id),
    user_id     TEXT        NOT NULL,
    rating      SMALLINT    NOT NULL CHECK (rating IN (-1, 1)),
    reason      TEXT,       -- wrong | outdated | missing_source | cant_open_source | other
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
