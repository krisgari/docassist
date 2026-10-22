package dev.docassist.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text utilities shared by the embedder, reranker and grounding checker. */
public final class Text {
    private Text() {}

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[.,][0-9]+)*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\\[(\"'*])");

    public static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from", "get", "has",
            "have", "how", "i", "if", "in", "is", "it", "its", "may", "me", "much", "my", "of", "on", "or",
            "our", "should", "that", "the", "their", "there", "this", "to", "was", "we", "what", "when",
            "where", "which", "who", "will", "with", "you", "your", "many", "any", "all", "about", "into",
            "than", "then", "them", "they", "these", "those", "so", "not", "no", "yes", "after", "before",
            "per", "each", "also", "only", "must", "up", "out", "over", "under", "need", "tell", "please");

    /** Estimated tokens (~4 characters per token for English prose). */
    public static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            out.add(m.group().replace(",", ""));
        }
        return out;
    }

    /** Lower-cased, stop-word-free, lightly stemmed content terms. */
    public static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        for (String t : tokens(text)) {
            if (STOPWORDS.contains(t)) continue;
            if (t.length() < 2 && !Character.isDigit(t.charAt(0))) continue;
            out.add(stem(t));
        }
        return out;
    }

    public static String stem(String t) {
        if (t.length() > 5 && t.endsWith("ing")) return t.substring(0, t.length() - 3);
        if (t.length() > 4 && t.endsWith("ies")) return t.substring(0, t.length() - 3) + "y";
        if (t.length() > 4 && t.endsWith("es") && !t.endsWith("ses")) return t.substring(0, t.length() - 1);
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) return t.substring(0, t.length() - 1);
        return t;
    }

    public static boolean isNumber(String token) {
        return !token.isEmpty() && Character.isDigit(token.charAt(0));
    }

    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\n+")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            for (String s : SENTENCE_END.split(trimmed)) {
                if (!s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }
}
