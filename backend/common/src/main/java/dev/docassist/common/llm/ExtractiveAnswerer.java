package dev.docassist.common.llm;

import dev.docassist.common.guardrails.InjectionScanner;
import dev.docassist.common.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic answerer used by the mock LLM and as the last-resort fallback when the model fails the
 * grounding check twice: it returns the best-matching source sentences verbatim, each with its citation.
 * It never states anything that is not in a source, and it abstains when nothing matches well.
 */
public final class ExtractiveAnswerer {

    public static final String NOT_FOUND =
            "I couldn't find this in the documents you have access to.";

    private final InjectionScanner scanner = new InjectionScanner();
    private final Set<String> lowInformation;

    public ExtractiveAnswerer() {
        this(Set.of());
    }

    /**
     * @param lowInformationTerms words that appear across the whole corpus and carry no signal for matching, such
     *                            as the company's own name (configured per tenant, like a glossary)
     */
    public ExtractiveAnswerer(Set<String> lowInformationTerms) {
        Set<String> stems = new HashSet<>();
        for (String t : lowInformationTerms) stems.addAll(Text.terms(t));
        this.lowInformation = Set.copyOf(stems);
    }

    public record Answer(String text, boolean found, List<Integer> cited) {}

    private record Candidate(int source, String sentence, double score, boolean superseded, int order) {}

    public Answer answer(String question, List<SourceBlocks.Source> sources) {
        Set<String> q = new LinkedHashSet<>(Text.terms(question));
        q.removeAll(lowInformation);
        if (q.isEmpty() || sources.isEmpty()) return new Answer(NOT_FOUND, false, List.of());

        List<Candidate> cands = new ArrayList<>();
        int order = 0;
        for (SourceBlocks.Source s : sources) {
            if (s.flagged()) continue;
            Set<String> sectionTerms = new HashSet<>(Text.terms(s.section()));
            List<String> header = null;
            for (String line : s.text().split("\n")) {
                List<String> cells = cells(line);
                String sentenceSource;
                if (cells == null) {
                    header = null;
                    sentenceSource = line.trim();
                } else if (header == null) {
                    header = cells;          // first row of a table is its header
                    continue;
                } else if (isSeparator(line)) {
                    continue;
                } else {
                    sentenceSource = rowAsSentence(header, cells);
                }
                for (String sent : Text.sentences(sentenceSource)) {
                    if (scanner.isSuspicious(sent) || sent.startsWith("|")) continue;
                    Set<String> st = new HashSet<>(Text.terms(sent));
                    int hit = 0, sectionHit = 0;
                    for (String t : q) {
                        if (matches(t, st) || inRange(t, sent)) hit++;
                        else if (matches(t, sectionTerms)) sectionHit++;
                    }
                    if (hit == 0) continue;
                    double score = (hit + 0.5 * sectionHit) / q.size();
                    cands.add(new Candidate(s.n(), sent, score, "superseded".equals(s.status()), order++));
                }
            }
        }
        cands.sort(Comparator.comparingDouble((Candidate c) -> -(c.score() - (c.superseded() ? 0.25 : 0)))
                .thenComparingInt(Candidate::order));
        if (cands.isEmpty() || cands.get(0).score() < 0.4) return new Answer(NOT_FOUND, false, List.of());

        double best = cands.get(0).score();
        List<Candidate> picked = new ArrayList<>();
        for (Candidate c : cands) {
            if (picked.size() == 3) break;
            if (c.score() < best * 0.6) break;
            if (picked.stream().anyMatch(p -> p.sentence().equals(c.sentence()))) continue;
            picked.add(c);
        }
        picked.sort(Comparator.comparingInt(Candidate::order));

        StringBuilder sb = new StringBuilder();
        List<Integer> cited = new ArrayList<>();
        boolean usedSuperseded = false;
        for (Candidate c : picked) {
            String s = c.sentence().endsWith(".") ? c.sentence() : c.sentence() + ".";
            if (sb.length() > 0) sb.append(' ');
            sb.append(s, 0, s.length() - 1).append(" [").append(c.source()).append("].");
            if (!cited.contains(c.source())) cited.add(c.source());
            usedSuperseded |= c.superseded();
        }
        if (usedSuperseded) {
            sb.append("\n\nNote: part of this comes from a document marked as superseded; check the current version.");
        }
        return new Answer(sb.toString(), true, cited);
    }

    /** Exact term match, or a shared 5-character prefix for longer words (approve / approver, rotate / rotation). */
    private static boolean matches(String term, Set<String> terms) {
        if (terms.contains(term)) return true;
        if (term.length() < 5 || Text.isNumber(term)) return false;
        String prefix = term.substring(0, 5);
        for (String t : terms) if (t.length() >= 5 && t.startsWith(prefix)) return true;
        return false;
    }

    private static final java.util.regex.Pattern RANGE =
            java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)%?\\s*(?:to|-|–)\\s*(\\d+(?:\\.\\d+)?)%?");

    /** A number in the question falls inside a range stated in the sentence ("25" in "20% to 30%"). */
    private static boolean inRange(String term, String sentence) {
        if (!Text.isNumber(term)) return false;
        double v;
        try {
            v = Double.parseDouble(term);
        } catch (NumberFormatException e) {
            return false;
        }
        java.util.regex.Matcher m = RANGE.matcher(sentence);
        while (m.find()) {
            double lo = Double.parseDouble(m.group(1)), hi = Double.parseDouble(m.group(2));
            if (lo < hi && v >= lo && v <= hi) return true;
        }
        return false;
    }

    private static List<String> cells(String line) {
        String t = line.trim();
        if (!t.startsWith("|")) return null;
        List<String> out = new ArrayList<>();
        String inner = t.substring(1, t.endsWith("|") ? t.length() - 1 : t.length());
        for (String cell : inner.split("\\|", -1)) out.add(cell.trim());
        return out;
    }

    private static boolean isSeparator(String line) {
        return line.replace("|", "").replace("-", "").replace(":", "").isBlank();
    }

    /** Header "Level | Title | Base salary" + row "E5 | Staff Engineer | 215,000" -> "Level E5; Title Staff Engineer; ..." */
    private static String rowAsSentence(List<String> header, List<String> row) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            String h = i < header.size() ? header.get(i) : "";
            parts.add(h.isEmpty() ? row.get(i) : h + ": " + row.get(i));
        }
        return String.join("; ", parts) + ".";
    }
}
