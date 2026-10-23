package dev.docassist.common.embedding;

import dev.docassist.common.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Redis cache for query embeddings, keyed by hash(model, text). Embeddings contain no ACL-sensitive data on
 * their own and are deterministic per model, so this cache is safe to share across users. Redis being down
 * degrades to a direct call rather than failing the request.
 */
public class CachingEmbedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(CachingEmbedder.class);

    private final Embedder delegate;
    private final StringRedisTemplate redis;

    public CachingEmbedder(Embedder delegate, StringRedisTemplate redis) {
        this.delegate = delegate;
        this.redis = redis;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public float[] embedQuery(String text) {
        String key = "emb:" + delegate.modelId() + ":" + Hashing.shortHash(text);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return decode(cached);
        } catch (RuntimeException e) {
            log.debug("embedding cache read failed: {}", e.getMessage());
        }
        float[] v = delegate.embedQuery(text);
        try {
            redis.opsForValue().set(key, encode(v), Duration.ofDays(7));
        } catch (RuntimeException e) {
            log.debug("embedding cache write failed: {}", e.getMessage());
        }
        return v;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return delegate.embedDocuments(texts);
    }

    private static String encode(float[] v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * 4);
        for (float f : v) buf.putFloat(f);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static float[] decode(String s) {
        ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(s));
        float[] v = new float[buf.remaining() / 4];
        for (int i = 0; i < v.length; i++) v[i] = buf.getFloat();
        return v;
    }
}
