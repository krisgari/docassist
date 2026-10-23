package dev.docassist.common.embedding;

import dev.docassist.common.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, offline embedder (signed feature hashing over unigrams and bigrams). It lets the stack and
 * the eval suite run with no network or API key. It is lexical in nature, so hybrid search still relies on the
 * BM25 leg for exact identifiers; switch to {@code voyage} for real semantic recall.
 */
public final class HashingEmbedder implements Embedder {

    @Override
    public String modelId() {
        return "local-hash-v1-512";
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(embed(t));
        return out;
    }

    float[] embed(String text) {
        float[] v = new float[DIMENSIONS];
        List<String> terms = Text.terms(text);
        for (int i = 0; i < terms.size(); i++) {
            add(v, terms.get(i), 1.0f);
            if (i + 1 < terms.size()) add(v, terms.get(i) + "_" + terms.get(i + 1), 0.5f);
        }
        double norm = 0;
        for (float x : v) norm += x * x;
        if (norm == 0) {
            v[0] = 1f; // avoid a zero vector, which cosine distance cannot handle
            return v;
        }
        float inv = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return v;
    }

    private static void add(float[] v, String feature, float weight) {
        int h = mix(feature.hashCode());
        int bucket = Math.floorMod(h, DIMENSIONS);
        float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
        v[bucket] += sign * weight;
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
