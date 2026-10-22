package dev.docassist.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache key conventions and the index version counter.
 *
 * <p>Retrieval results are cached by (tenant, index version, ACL fingerprint, query hash). Any index write bumps
 * the version, so stale entries become unreachable immediately and simply expire; no scan-and-delete needed.
 * Group membership is cached per user with a short TTL and purged on ACL events.</p>
 */
@Component
public class CacheKeys {

    private static final Logger log = LoggerFactory.getLogger(CacheKeys.class);

    private final StringRedisTemplate redis;

    public CacheKeys(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String indexVersionKey(String tenant) {
        return "idxver:" + tenant;
    }

    public static String retrievalKey(String tenant, long version, String aclFingerprint, String queryHash) {
        return "retr:" + tenant + ":" + version + ":" + aclFingerprint + ":" + queryHash;
    }

    public static String groupsKey(String tenant, String userId) {
        return "groups:" + tenant + ":" + userId;
    }

    public long indexVersion(String tenant) {
        try {
            String v = redis.opsForValue().get(indexVersionKey(tenant));
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            log.debug("index version read failed: {}", e.getMessage());
            return -1; // -1 disables retrieval caching for this request
        }
    }

    public void bumpIndexVersion(String tenant) {
        try {
            redis.opsForValue().increment(indexVersionKey(tenant));
        } catch (RuntimeException e) {
            log.warn("could not bump index version; retrieval cache may serve stale ids until TTL: {}", e.getMessage());
        }
    }

    public void purgeGroups(String tenant) {
        try {
            var keys = redis.keys("groups:" + tenant + ":*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (RuntimeException e) {
            log.warn("group cache purge failed: {}", e.getMessage());
        }
    }
}
