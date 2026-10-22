package dev.docassist.common.embedding;

import java.util.List;

public interface Embedder {
    int DIMENSIONS = 512;

    /** Identifier stored on every chunk; a change means a re-embed into a shadow index. */
    String modelId();

    float[] embedQuery(String text);

    List<float[]> embedDocuments(List<String> texts);

    static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
