package dev.docassist.common.guardrails;

import dev.docassist.common.util.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that each answer sentence is supported by the passages it cites.
 *
 * <p>Cheap and deterministic: a sentence is supported when its numbers all appear in the cited text and most
 * of its content terms do. Production deployments put an NLI model or a small LLM verifier behind the same
 * interface; this version is what runs in CI and in the offline demo.</p>
 */
public final class GroundingChecker {

    public enum Status { SUPPORTED, WEAK, UNSUPPORTED, UNCITED, NON_FACTUAL }

    public record SentenceCheck(String sentence, List<Integer> citations, Status status, double termSupport) {}

    public record Report(List<SentenceCheck> sentences, List<Integer> invalidCitations) {
        public long factualCount() {
            return sentences.stream().filter(s -> s.status() != Status.NON_FACTUAL).count();
        }

        public long failingCount() {
            return sentences.stream()
                    .filter(s -> s.status() == Status.UNSUPPORTED || s.status() == Status.UNCITED)
                    .count();
        }

        /** Share of factual sentences that failed the check. */
        public double failureRate() {
            long factual = factualCount();
            return factual == 0 ? 0 : (double) failingCount() / factual;
        }
    }

    private static final Pattern CITE = Pattern.compile("\\[(\\d{1,2})]");

    private final double supportThreshold;

    public GroundingChecker() {
        this(0.6);
    }

    public GroundingChecker(double supportThreshold) {
        this.supportThreshold = supportThreshold;
    }

    /**
     * @param answer  model answer with citation markers like [1]
     * @param sources citation number -> passage text the model was given
     */
    public Report check(String answer, Map<Integer, String> sources) {
        List<SentenceCheck> out = new ArrayList<>();
        Set<Integer> invalid = new HashSet<>();
        Integer lastCitation = null;

        for (String sentence : Text.sentences(answer)) {
            List<Integer> cites = new ArrayList<>();
            Matcher m = CITE.matcher(sentence);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (sources.containsKey(n)) cites.add(n);
                else invalid.add(n);
            }
            String clean = CITE.matcher(sentence).replaceAll(" ");
            List<String> terms = Text.terms(clean);
            boolean hasNumber = terms.stream().anyMatch(Text::isNumber);

            if (!isFactual(clean, terms, hasNumber)) {
                out.add(new SentenceCheck(sentence, cites, Status.NON_FACTUAL, 1.0));
                continue;
            }
            // A sentence continuing a cited bullet list may rely on the previous citation.
            List<Integer> effective = cites.isEmpty() && lastCitation != null && sentence.startsWith("-")
                    ? List.of(lastCitation) : cites;
            if (effective.isEmpty()) {
                out.add(new SentenceCheck(sentence, cites, Status.UNCITED, 0));
                continue;
            }
            Set<String> support = new HashSet<>();
            for (int c : effective) support.addAll(Text.terms(sources.get(c)));

            int hit = 0;
            boolean numbersOk = true;
            for (String t : terms) {
                if (support.contains(t)) hit++;
                else if (Text.isNumber(t)) numbersOk = false;
            }
            double ratio = terms.isEmpty() ? 1.0 : (double) hit / terms.size();
            Status status;
            if (!numbersOk) status = Status.UNSUPPORTED;
            else if (ratio >= supportThreshold) status = Status.SUPPORTED;
            else if (ratio >= supportThreshold / 2) status = Status.WEAK;
            else status = Status.UNSUPPORTED;
            out.add(new SentenceCheck(sentence, cites, status, ratio));
            if (!cites.isEmpty()) lastCitation = cites.get(cites.size() - 1);
        }
        return new Report(out, List.copyOf(invalid));
    }

    private static boolean isFactual(String sentence, List<String> terms, boolean hasNumber) {
        if (hasNumber) return true;
        String s = sentence.trim().toLowerCase();
        if (s.endsWith("?") || s.endsWith(":")) return false;
        if (s.startsWith("i couldn't") || s.startsWith("i could not") || s.startsWith("i can't")
                || s.startsWith("let me know") || s.startsWith("if you") || s.startsWith("note:")
                || s.startsWith("sources") || s.startsWith("#")) {
            return false;
        }
        return terms.size() >= 5;
    }
}
