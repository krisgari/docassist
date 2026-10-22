package dev.docassist.api.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.docassist.common.cache.CacheKeys;
import dev.docassist.common.kafka.Events;
import dev.docassist.common.kafka.Json;
import dev.docassist.common.kafka.Topics;
import dev.docassist.common.util.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates async jobs idempotently. The key includes the corpus version, so the same request on an unchanged
 * corpus reuses the existing job (no double work on retries or double clicks) while a corpus change gets a
 * fresh run.
 */
@Service
public class JobService {

    public record JobView(String jobId, String type, String status, int progress, String note,
                          JsonNode result, String error, double costUsd, Instant createdAt, boolean reused) {}

    private final JdbcTemplate jdbc;
    private final org.springframework.kafka.core.KafkaTemplate<String, String> kafka;
    private final Json json;
    private final ObjectMapper mapper;
    private final CacheKeys cacheKeys;

    public JobService(JdbcTemplate jdbc, org.springframework.kafka.core.KafkaTemplate<String, String> kafka,
                      Json json, ObjectMapper mapper, CacheKeys cacheKeys) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.json = json;
        this.mapper = mapper;
        this.cacheKeys = cacheKeys;
    }

    public JobView submit(String tenant, String userId, String type, Map<String, Object> args) {
        String key = Hashing.shortHash(tenant + "|" + userId + "|" + type + "|" + json.write(args) + "|v"
                + cacheKeys.indexVersion(tenant));
        List<String> inserted = jdbc.queryForList("""
                INSERT INTO job (tenant_id, user_id, job_type, args, idempotency_key)
                VALUES (?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                RETURNING job_id::text
                """, String.class, tenant, userId, type, json.write(args), key);

        if (inserted.isEmpty()) {
            JobView existing = jdbc.queryForObject(
                    "SELECT job_id::text FROM job WHERE tenant_id = ? AND idempotency_key = ?",
                    (rs, n) -> get(tenant, userId, rs.getString(1)).orElseThrow(), tenant, key);
            if (!"failed".equals(existing.status())) {
                return new JobView(existing.jobId(), existing.type(), existing.status(), existing.progress(),
                        existing.note(), existing.result(), existing.error(), existing.costUsd(), existing.createdAt(), true);
            }
            // A failed job may be retried explicitly: reset it and re-queue under the same id.
            jdbc.update("UPDATE job SET status = 'queued', progress = 0, error = NULL, updated_at = now() WHERE job_id = ?::uuid",
                    existing.jobId());
            publish(tenant, userId, type, args, existing.jobId());
            return get(tenant, userId, existing.jobId()).orElseThrow();
        }
        String jobId = inserted.get(0);
        publish(tenant, userId, type, args, jobId);
        return get(tenant, userId, jobId).orElseThrow();
    }

    private void publish(String tenant, String userId, String type, Map<String, Object> args, String jobId) {
        kafka.send(Topics.JOB_REQUESTS, Topics.key(tenant, jobId),
                json.write(new Events.JobRequested(jobId, tenant, userId, type, args, Instant.now())));
    }

    /** Users can only see their own jobs. */
    public Optional<JobView> get(String tenant, String userId, String jobId) {
        List<JobView> rows = jdbc.query("""
                SELECT job_id::text, job_type, status, progress, progress_note, result::text, error, cost_usd, created_at
                FROM job WHERE tenant_id = ? AND user_id = ? AND job_id = ?::uuid
                """, (rs, n) -> new JobView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), parse(rs.getString(6)), rs.getString(7), rs.getDouble(8),
                rs.getTimestamp(9).toInstant(), false), tenant, userId, jobId);
        return rows.stream().findFirst();
    }

    public List<JobView> recent(String tenant, String userId) {
        return jdbc.query("""
                SELECT job_id::text, job_type, status, progress, progress_note, NULL, error, cost_usd, created_at
                FROM job WHERE tenant_id = ? AND user_id = ? ORDER BY created_at DESC LIMIT 20
                """, (rs, n) -> new JobView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), null, rs.getString(7), rs.getDouble(8), rs.getTimestamp(9).toInstant(), false),
                tenant, userId);
    }

    private JsonNode parse(String s) {
        if (s == null) return null;
        try {
            JsonNode n = mapper.readTree(s);
            return n.has("partials") && !n.has("summary") ? null : n;
        } catch (Exception e) {
            return null;
        }
    }
}
