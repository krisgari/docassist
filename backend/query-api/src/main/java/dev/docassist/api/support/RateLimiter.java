package dev.docassist.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Fixed-window per-user limit in Redis. Fails open if Redis is down (availability over strictness for a limiter). */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    private final int perMinute;

    public RateLimiter(StringRedisTemplate redis, @Value("${docassist.rate-limit.chat-per-minute}") int perMinute) {
        this.redis = redis;
        this.perMinute = perMinute;
    }

    public boolean allow(String tenant, String userId) {
        String key = "rl:chat:" + tenant + ":" + userId + ":" + (System.currentTimeMillis() / 60_000);
        try {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1) redis.expire(key, Duration.ofSeconds(70));
            return n == null || n <= perMinute;
        } catch (RuntimeException e) {
            log.debug("rate limiter unavailable: {}", e.getMessage());
            return true;
        }
    }
}
