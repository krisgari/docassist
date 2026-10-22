package dev.docassist.api.audit;

import dev.docassist.common.kafka.Json;
import dev.docassist.common.util.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * One row per answered turn: who asked, what was retrieved, what was dropped by permissions, which guardrails
 * fired, which tools ran, model, tokens, cost, latency, outcome. The answer itself is stored as a hash; the full
 * text would follow the tenant's retention policy in production.
 */
@Service
public class AuditService {

    public record Entry(String tenantId, String userId, String question, String answer,
                        List<String> retrievedDocIds, List<String> citedDocIds, List<String> droppedByAcl,
                        List<?> guardrailEvents, List<?> toolCalls, String model, int inputTokens, int outputTokens,
                        double costUsd, long latencyMs, String outcome) {}

    private final JdbcTemplate jdbc;
    private final Json json;

    public AuditService(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID record(Entry e) {
        return jdbc.queryForObject("""
                INSERT INTO audit_log (tenant_id, user_id, question, answer_hash, retrieved_doc_ids, cited_doc_ids,
                                       dropped_by_acl, guardrail_events, tool_calls, model, input_tokens, output_tokens,
                                       cost_usd, latency_ms, outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                RETURNING audit_id
                """, UUID.class,
                e.tenantId(), e.userId(), e.question(), e.answer() == null ? null : Hashing.sha256(e.answer()),
                e.retrievedDocIds().toArray(new String[0]), e.citedDocIds().toArray(new String[0]),
                e.droppedByAcl().toArray(new String[0]), json.write(e.guardrailEvents()), json.write(e.toolCalls()),
                e.model(), e.inputTokens(), e.outputTokens(), e.costUsd(), (int) e.latencyMs(), e.outcome());
    }
}
