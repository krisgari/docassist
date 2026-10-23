package dev.docassist.common.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal rank fusion: score(d) = sum over rankings of 1 / (k + rank(d)). Robust to score scales. */
public final class Rrf {
    private Rrf() {}

    public static final int DEFAULT_K = 60;

    public static Map<String, Double> fuse(List<List<String>> rankings, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                scores.merge(ranking.get(rank), 1.0 / (k + rank + 1), Double::sum);
            }
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Double> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : entries) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }
}
