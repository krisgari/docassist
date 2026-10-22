package dev.docassist.api.audit;

import dev.docassist.api.security.CurrentUser;
import dev.docassist.common.security.PermissionService;
import dev.docassist.common.security.Principals;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator view: quality, cost, latency, freshness and recent traces. Restricted to the "execs" group in the demo
 * (an "assistant-admins" group in a real deployment). Questions are shown; answers are not stored in clear.
 */
@RestController
public class InsightsController {

    private final JdbcTemplate jdbc;
    private final PermissionService permissions;

    public InsightsController(JdbcTemplate jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    @GetMapping("/api/admin/insights")
    public Map<String, Object> insights(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt);
        Principals p = permissions.principalsFor(u.tenantId(), u.userId());
        if (!p.groups().contains("execs")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admins only");
        String t = u.tenantId();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", jdbc.queryForMap("""
                SELECT count(*) AS questions,
                       coalesce(round(avg(cost_usd)::numeric, 5), 0) AS avg_cost_usd,
                       coalesce(sum(cost_usd), 0) AS total_cost_usd,
                       coalesce(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms), 0) AS p50_latency_ms,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms), 0) AS p95_latency_ms,
                       count(*) FILTER (WHERE outcome = 'not_found') AS not_found,
                       count(*) FILTER (WHERE outcome = 'partial') AS partial,
                       count(*) FILTER (WHERE outcome = 'error') AS errors,
                       count(*) FILTER (WHERE cardinality(dropped_by_acl) > 0) AS acl_drift_events
                FROM audit_log WHERE tenant_id = ? AND created_at > now() - interval '7 days'
                """, t));
        out.put("feedback", jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE rating = 1) AS up, count(*) FILTER (WHERE rating = -1) AS down
                FROM feedback WHERE tenant_id = ?
                """, t));
        out.put("guardrails", jdbc.queryForList("""
                SELECT e->>'type' AS type, count(*) AS n
                FROM audit_log, jsonb_array_elements(guardrail_events) e
                WHERE tenant_id = ? GROUP BY 1 ORDER BY 2 DESC
                """, t));
        out.put("index", jdbc.queryForList("""
                SELECT d.doc_id, d.title, d.status, d.sensitivity, d.modified_at, d.indexed_at,
                       cardinality(d.acl_principals) AS acl_entries, count(c.ordinal) AS chunks,
                       bool_or(coalesce(c.injection_flag, false)) AS injection_flag
                FROM document d LEFT JOIN chunk c ON c.tenant_id = d.tenant_id AND c.doc_id = d.doc_id
                WHERE d.tenant_id = ? GROUP BY d.tenant_id, d.doc_id ORDER BY d.indexed_at DESC
                """, t));
        List<Map<String, Object>> recent = jdbc.queryForList("""
                SELECT a.audit_id, a.user_id, a.question, a.outcome, a.latency_ms, a.cost_usd, a.cited_doc_ids,
                       a.dropped_by_acl, jsonb_array_length(a.tool_calls) AS tool_calls, a.guardrail_events, a.created_at,
                       f.rating, f.reason
                FROM audit_log a LEFT JOIN feedback f ON f.audit_id = a.audit_id
                WHERE a.tenant_id = ? ORDER BY a.created_at DESC LIMIT 25
                """, t);
        recent.forEach(r -> r.replaceAll((k, v) -> v instanceof java.sql.Array arr ? toList(arr) : v instanceof org.postgresql.util.PGobject o ? o.getValue() : v));
        out.put("recent", recent);
        return out;
    }

    private static Object toList(java.sql.Array arr) {
        try {
            return List.of((Object[]) arr.getArray());
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }
}
