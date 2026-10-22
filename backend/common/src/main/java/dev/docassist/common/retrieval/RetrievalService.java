package dev.docassist.common.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.docassist.common.cache.CacheKeys;
import dev.docassist.common.config.DocassistProperties;
import dev.docassist.common.embedding.Embedder;
import dev.docassist.common.security.Principals;
import dev.docassist.common.util.Hashing;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval: dense (pgvector HNSW) + lexical (Postgres full text) → reciprocal rank fusion → rerank.
 * Tenant and ACL filters are applied inside both queries. Results are cached as chunk keys (never text) under a
 * key that includes the caller's ACL fingerprint and the index version.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    public record Filters(String docType, String sourceSystem, Instant modifiedAfter, boolean includeSuperseded) {
        public static Filters none() {
            return new Filters(null, null, null, true);
        }
    }

    public record Result(List<RetrievedChunk> chunks, int candidates, boolean cacheHit, long millis) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Embedder embedder;
    private final Reranker reranker;
    private final StringRedisTemplate redis;
    private final CacheKeys cacheKeys;
    private final DocassistProperties props;
    private final ObjectMapper mapper;
    private final Timer timer;
    private boolean iterativeScan;

    public RetrievalService(JdbcTemplate jdbc, TransactionTemplate tx, Embedder embedder, Reranker reranker,
                            StringRedisTemplate redis, CacheKeys cacheKeys, DocassistProperties props,
                            ObjectMapper mapper, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.embedder = embedder;
        this.reranker = reranker;
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.props = props;
        this.mapper = mapper;
        this.timer = Timer.builder("docassist.retrieval").publishPercentiles(0.5, 0.95).register(meters);
    }

    /**
     * pgvector >= 0.8 can keep scanning the HNSW graph until enough rows pass the WHERE clause. Without it, a
     * restrictive ACL filter on a large index can return fewer than k rows (the post-filter starvation problem).
     */
    @PostConstruct
    void detectIterativeScan() {
        try {
            String v = jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
            String[] p = v == null ? new String[]{"0", "0"} : v.split("\\.");
            iterativeScan = Integer.parseInt(p[0]) > 0 || Integer.parseInt(p[1]) >= 8;
            log.info("pgvector {} (iterative index scan: {})", v, iterativeScan);
        } catch (RuntimeException e) {
            log.warn("could not detect pgvector version: {}", e.getMessage());
        }
    }

    public Result search(Principals p, String query, Filters filters) {
        long start = System.nanoTime();
        int topK = props.getRetrieval().getTopK();
        String[] acl = p.all().toArray(new String[0]);

        long version = cacheKeys.indexVersion(p.tenantId());
        String cacheKey = version < 0 ? null : CacheKeys.retrievalKey(p.tenantId(), version, p.aclFingerprint(),
                Hashing.shortHash(query.toLowerCase(Locale.ROOT).trim() + "|" + filters));
        Map<String, Double> fused = cacheKey == null ? null : readCache(cacheKey);
        boolean hit = fused != null;

        if (fused == null) {
            float[] qv = embedder.embedQuery(query);
            List<String> dense = denseSearch(p.tenantId(), acl, qv, filters);
            List<String> lexical = lexicalSearch(p.tenantId(), acl, query, filters);
            fused = Rrf.fuse(List.of(dense, lexical), Rrf.DEFAULT_K);
            if (cacheKey != null) writeCache(cacheKey, fused);
        }

        List<RetrievedChunk> candidates = load(p.tenantId(), acl, fused);
        List<RetrievedChunk> ranked = reranker.rerank(query, candidates, topK);
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        timer.record(Duration.ofMillis(ms));
        return new Result(ranked, candidates.size(), hit, ms);
    }

    private List<String> denseSearch(String tenant, String[] acl, float[] qv, Filters f) {
        String vec = Embedder.toPgVector(qv);
        StringBuilder sql = new StringBuilder("""
                SELECT doc_id || '#' || ordinal AS k FROM chunk
                WHERE tenant_id = ? AND status <> 'deleted' AND acl_principals && ?::text[]
                """);
        List<Object> args = new ArrayList<>(List.of(tenant, acl));
        appendFilters(sql, args, f, tenant);
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        args.add(vec);
        args.add(props.getRetrieval().getDenseK());
        return tx.execute(status -> {
            if (iterativeScan) jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
            return jdbc.queryForList(sql.toString(), String.class, args.toArray());
        });
    }

    private List<String> lexicalSearch(String tenant, String[] acl, String query, Filters f) {
        String tsq = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() > 1)
                .distinct()
                .collect(Collectors.joining(" | "));
        if (tsq.isBlank()) return List.of();
        StringBuilder sql = new StringBuilder("""
                SELECT c.doc_id || '#' || c.ordinal AS k
                FROM chunk c, to_tsquery('english', ?) q
                WHERE c.tenant_id = ? AND c.status <> 'deleted' AND c.acl_principals && ?::text[] AND c.tsv @@ q
                """);
        List<Object> args = new ArrayList<>(List.of(tsq, tenant, acl));
        appendFilters(sql, args, f, tenant);
        sql.append(" ORDER BY ts_rank_cd(c.tsv, q) DESC LIMIT ?");
        args.add(props.getRetrieval().getLexicalK());
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }

    /** Metadata filters extracted from the query; unqualified columns resolve to the chunk table in both queries. */
    private static void appendFilters(StringBuilder sql, List<Object> args, Filters f, String tenant) {
        if (f == null) return;
        if (!f.includeSuperseded()) sql.append(" AND status <> 'superseded'");
        if (f.modifiedAfter() != null) {
            sql.append(" AND modified_at >= ?");
            args.add(Timestamp.from(f.modifiedAfter()));
        }
        if (f.docType() != null || f.sourceSystem() != null) {
            sql.append(" AND doc_id IN (SELECT d.doc_id FROM document d WHERE d.tenant_id = ?");
            args.add(tenant);
            if (f.docType() != null) {
                sql.append(" AND d.doc_type = ?");
                args.add(f.docType());
            }
            if (f.sourceSystem() != null) {
                sql.append(" AND d.source_system = ?");
                args.add(f.sourceSystem());
            }
            sql.append(")");
        }
    }

    /** Loads chunk rows for fused keys, re-applying tenant + ACL filters (cached ids are never trusted alone). */
    private List<RetrievedChunk> load(String tenant, String[] acl, Map<String, Double> fused) {
        if (fused.isEmpty()) return List.of();
        String[] keys = fused.keySet().toArray(new String[0]);
        Map<String, RetrievedChunk> byKey = new HashMap<>();
        jdbc.query("""
                SELECT c.doc_id, c.ordinal, c.parent_ordinal, c.section_path, c.context_header, c.body, c.status,
                       c.injection_flag, c.acl_principals, c.modified_at, d.title, d.url, d.source_system, d.sensitivity
                FROM chunk c JOIN document d ON d.tenant_id = c.tenant_id AND d.doc_id = c.doc_id
                WHERE c.tenant_id = ? AND c.status <> 'deleted' AND c.acl_principals && ?::text[]
                  AND (c.doc_id || '#' || c.ordinal) = ANY(?)
                """, rs -> {
            RetrievedChunk rc = map(rs, 0);
            byKey.put(rc.key(), rc);
        }, tenant, acl, keys);
        List<RetrievedChunk> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            RetrievedChunk rc = byKey.get(e.getKey());
            if (rc != null) out.add(rc.withFused(e.getValue()));
        }
        return out;
    }

    /** Small-to-big: replace each hit's body with its whole section (bounded), so the model sees full context. */
    public List<RetrievedChunk> expandToSections(String tenant, List<RetrievedChunk> hits, int maxTokensPerHit) {
        List<RetrievedChunk> out = new ArrayList<>();
        for (RetrievedChunk h : hits) {
            List<String> bodies = jdbc.queryForList("""
                    SELECT body FROM chunk WHERE tenant_id = ? AND doc_id = ? AND parent_ordinal = ?
                      AND status <> 'deleted' ORDER BY ordinal
                    """, String.class, tenant, h.docId(), h.parentOrdinal());
            String section = String.join("\n\n", bodies);
            out.add(section.length() / 4 <= maxTokensPerHit && !section.isBlank() ? h.withBody(section) : h);
        }
        return out;
    }

    /** Full current text of one document (used by the fetch_doc tool after a permission check). */
    public List<RetrievedChunk> documentChunks(String tenant, String[] acl, String docId) {
        return jdbc.query("""
                SELECT c.doc_id, c.ordinal, c.parent_ordinal, c.section_path, c.context_header, c.body, c.status,
                       c.injection_flag, c.acl_principals, c.modified_at, d.title, d.url, d.source_system, d.sensitivity
                FROM chunk c JOIN document d ON d.tenant_id = c.tenant_id AND d.doc_id = c.doc_id
                WHERE c.tenant_id = ? AND c.doc_id = ? AND c.status <> 'deleted' AND c.acl_principals && ?::text[]
                ORDER BY c.ordinal
                """, (rs, n) -> map(rs, 0), tenant, docId, acl);
    }

    private static RetrievedChunk map(ResultSet rs, double fused) throws SQLException {
        Array arr = rs.getArray("acl_principals");
        Timestamp ts = rs.getTimestamp("modified_at");
        return new RetrievedChunk(
                rs.getString("doc_id"), rs.getInt("ordinal"), rs.getInt("parent_ordinal"),
                rs.getString("title"), rs.getString("url"), rs.getString("source_system"),
                rs.getString("section_path"), rs.getString("context_header"), rs.getString("body"),
                rs.getString("status"), rs.getString("sensitivity"), rs.getBoolean("injection_flag"),
                Arrays.asList((String[]) arr.getArray()), ts == null ? null : ts.toInstant(),
                0, 0, fused, 0);
    }

    private Map<String, Double> readCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return null;
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Double>>() {});
        } catch (Exception e) {
            log.debug("retrieval cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, Map<String, Double> fused) {
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(fused),
                    Duration.ofSeconds(props.getRetrieval().getRetrievalCacheTtlSeconds()));
        } catch (Exception e) {
            log.debug("retrieval cache write failed: {}", e.getMessage());
        }
    }
}
