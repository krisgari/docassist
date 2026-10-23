package dev.docassist.worker.ingest;

import dev.docassist.common.cache.CacheKeys;
import dev.docassist.common.chunking.Chunk;
import dev.docassist.common.chunking.FrontMatter;
import dev.docassist.common.chunking.MarkdownChunker;
import dev.docassist.common.embedding.Embedder;
import dev.docassist.common.guardrails.InjectionScanner;
import dev.docassist.common.util.Hashing;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk → scan → embed → write. Idempotent: replaying the same event produces the same rows, and an unchanged
 * document is a no-op. Only chunks whose content hash changed are re-embedded.
 */
@Service
public class IndexWriter {

    private static final Logger log = LoggerFactory.getLogger(IndexWriter.class);

    public enum Outcome { INDEXED, UNCHANGED, DELETED, NOT_FOUND }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Embedder embedder;
    private final InjectionScanner scanner;
    private final CacheKeys cacheKeys;
    private final MeterRegistry meters;
    private final MarkdownChunker chunker = new MarkdownChunker();

    public IndexWriter(JdbcTemplate jdbc, TransactionTemplate tx, Embedder embedder, InjectionScanner scanner,
                       CacheKeys cacheKeys, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.embedder = embedder;
        this.scanner = scanner;
        this.cacheKeys = cacheKeys;
        this.meters = meters;
    }

    public Outcome upsert(String tenant, FrontMatter fm, String contentHash, long sourceVersion) {
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT content_hash, source_version FROM document WHERE tenant_id = ? AND doc_id = ? AND status <> 'deleted'",
                tenant, fm.id());
        if (!existing.isEmpty() && contentHash.equals(existing.get(0).get("content_hash"))) {
            return Outcome.UNCHANGED;
        }
        if (!existing.isEmpty() && ((Number) existing.get(0).get("source_version")).longValue() > sourceVersion) {
            log.info("skipping out-of-order event for {} (have newer version)", fm.id());
            return Outcome.UNCHANGED;
        }

        List<Chunk> chunks = chunker.chunk(fm.title(), fm.body());

        // Reuse embeddings for chunks whose text did not change.
        Map<String, String> reusable = new HashMap<>();
        jdbc.query("SELECT content_hash, embedding::text AS e FROM chunk WHERE tenant_id = ? AND doc_id = ? AND embedding_model = ?",
                rs -> { reusable.put(rs.getString("content_hash"), rs.getString("e")); },
                tenant, fm.id(), embedder.modelId());

        List<String> hashes = new ArrayList<>();
        List<String> toEmbed = new ArrayList<>();
        for (Chunk c : chunks) {
            String h = Hashing.sha256(c.embeddingText());
            hashes.add(h);
            if (!reusable.containsKey(h)) toEmbed.add(c.embeddingText());
        }
        List<float[]> fresh = toEmbed.isEmpty() ? List.of() : embedder.embedDocuments(toEmbed);
        meters.counter("docassist.ingest.chunks_embedded").increment(fresh.size());
        meters.counter("docassist.ingest.chunks_reused").increment(chunks.size() - fresh.size());

        List<String> vectors = new ArrayList<>();
        int f = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String v = reusable.get(hashes.get(i));
            vectors.add(v != null ? v : Embedder.toPgVector(fresh.get(f++)));
        }

        String[] acl = fm.acl().toArray(new String[0]);
        String docStatus = "superseded".equals(fm.status()) ? "superseded" : "active";
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO document (tenant_id, doc_id, source_system, title, url, doc_type, owner, sensitivity,
                                          status, acl_principals, source_version, content_hash, modified_at, indexed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (tenant_id, doc_id) DO UPDATE SET
                      source_system = EXCLUDED.source_system, title = EXCLUDED.title, url = EXCLUDED.url,
                      doc_type = EXCLUDED.doc_type, owner = EXCLUDED.owner, sensitivity = EXCLUDED.sensitivity,
                      status = EXCLUDED.status, acl_principals = EXCLUDED.acl_principals,
                      source_version = EXCLUDED.source_version, content_hash = EXCLUDED.content_hash,
                      modified_at = EXCLUDED.modified_at, indexed_at = now()
                    """,
                    tenant, fm.id(), fm.source(), fm.title(), fm.url(), fm.type(), fm.owner(), fm.sensitivity(),
                    docStatus, acl, sourceVersion, contentHash, Timestamp.from(fm.modified()));
            jdbc.update("DELETE FROM chunk WHERE tenant_id = ? AND doc_id = ?", tenant, fm.id());
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                boolean flagged = scanner.isSuspicious(c.sectionPath() + "\n" + c.body());
                jdbc.update("""
                        INSERT INTO chunk (tenant_id, doc_id, ordinal, parent_ordinal, section_path, context_header, body,
                                           token_count, content_hash, acl_principals, status, modified_at,
                                           embedding_model, embedding, injection_flag)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
                        """,
                        tenant, fm.id(), c.ordinal(), c.parentOrdinal(), c.sectionPath(), c.contextHeader(), c.body(),
                        c.tokenCount(), hashes.get(i), acl, docStatus, Timestamp.from(fm.modified()),
                        embedder.modelId(), vectors.get(i), flagged);
                if (flagged) log.warn("prompt-injection pattern in {} '{}' (indexed, flagged)", fm.id(), c.sectionPath());
            }
        });
        cacheKeys.bumpIndexVersion(tenant);
        log.info("indexed {} ({} chunks, {} embedded, {} reused)", fm.id(), chunks.size(), fresh.size(), chunks.size() - fresh.size());
        return Outcome.INDEXED;
    }

    public Outcome delete(String tenant, String docId) {
        int n = tx.execute(status -> {
            jdbc.update("UPDATE chunk SET status = 'deleted' WHERE tenant_id = ? AND doc_id = ?", tenant, docId);
            return jdbc.update("UPDATE document SET status = 'deleted', indexed_at = now() WHERE tenant_id = ? AND doc_id = ?",
                    tenant, docId);
        });
        cacheKeys.bumpIndexVersion(tenant);
        log.info("tombstoned {}", docId);
        return n > 0 ? Outcome.DELETED : Outcome.NOT_FOUND;
    }

    public int updateAcl(String tenant, String docId, List<String> principals) {
        String[] acl = principals.toArray(new String[0]);
        int n = tx.execute(status -> {
            jdbc.update("UPDATE chunk SET acl_principals = ? WHERE tenant_id = ? AND doc_id = ?", acl, tenant, docId);
            return jdbc.update("UPDATE document SET acl_principals = ?, indexed_at = now() WHERE tenant_id = ? AND doc_id = ?",
                    acl, tenant, docId);
        });
        cacheKeys.bumpIndexVersion(tenant);
        return n;
    }
}
