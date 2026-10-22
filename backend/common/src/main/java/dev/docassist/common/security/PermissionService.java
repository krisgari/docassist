package dev.docassist.common.security;

import dev.docassist.common.cache.CacheKeys;
import dev.docassist.common.config.DocassistProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two layers of permission enforcement:
 * <ol>
 *   <li>{@link #principalsFor} builds the filter used <em>inside</em> the search query (never a post-filter).</li>
 *   <li>{@link #lateCheck} re-validates the final passages against the source-of-truth ACL right before they are
 *       shown to the model. In this demo the source of truth is the {@code document} table maintained by the
 *       connector; with SharePoint / Drive it is a batched call to the source's permission API.</li>
 * </ol>
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final DocassistProperties props;

    public PermissionService(JdbcTemplate jdbc, StringRedisTemplate redis, DocassistProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
    }

    public record LateCheckResult(List<String> allowedDocIds, List<String> deniedDocIds) {}

    public Principals principalsFor(String tenant, String userId) {
        String key = CacheKeys.groupsKey(tenant, userId);
        List<String> groups = null;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) groups = cached.isEmpty() ? List.of() : Arrays.asList(cached.split(","));
        } catch (RuntimeException e) {
            log.debug("group cache unavailable: {}", e.getMessage());
        }
        if (groups == null) {
            groups = jdbc.queryForList(
                    "SELECT group_id FROM group_member WHERE tenant_id = ? AND user_id = ? ORDER BY group_id",
                    String.class, tenant, userId);
            try {
                redis.opsForValue().set(key, String.join(",", groups),
                        Duration.ofSeconds(props.getRetrieval().getGroupCacheTtlSeconds()));
            } catch (RuntimeException e) {
                log.debug("group cache write failed: {}", e.getMessage());
            }
        }
        return Principals.of(tenant, userId, groups);
    }

    public boolean userExists(String tenant, String userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE tenant_id = ? AND user_id = ?",
                Integer.class, tenant, userId);
        return n != null && n > 0;
    }

    public LateCheckResult lateCheck(Principals p, Collection<String> docIds) {
        if (docIds.isEmpty()) return new LateCheckResult(List.of(), List.of());
        Map<String, List<String>> acl = new HashMap<>();
        Map<String, String> status = new HashMap<>();
        jdbc.query("SELECT doc_id, acl_principals, status FROM document WHERE tenant_id = ? AND doc_id = ANY(?)",
                rs -> {
                    Array arr = rs.getArray("acl_principals");
                    acl.put(rs.getString("doc_id"), Arrays.asList((String[]) arr.getArray()));
                    status.put(rs.getString("doc_id"), rs.getString("status"));
                },
                p.tenantId(), docIds.toArray(new String[0]));

        List<String> allowed = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (String id : docIds) {
            List<String> a = acl.get(id);
            boolean ok = a != null && !"deleted".equals(status.get(id)) && p.canSee(a);
            (ok ? allowed : denied).add(id);
        }
        if (!denied.isEmpty()) {
            // Index said visible, source says no: ACL drift. Logged for the re-sync job and the ops dashboard.
            log.warn("acl_drift tenant={} user={} docs={}", p.tenantId(), p.userId(), denied);
        }
        return new LateCheckResult(allowed, denied);
    }
}
