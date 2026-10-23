package dev.docassist.common.guardrails;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Flags document text that tries to instruct the model. Flagged chunks are down-ranked and wrapped with a
 * warning, never silently dropped (the user may legitimately be looking for that document). This is one
 * layer: the system prompt treats all retrieved text as data, and tools are capability-scoped, so a missed
 * pattern still cannot widen what the agent can read or do.
 */
public final class InjectionScanner {

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        int f = Pattern.CASE_INSENSITIVE;
        PATTERNS.put("ignore_instructions",
                Pattern.compile("\\b(ignore|disregard|forget)\\b.{0,20}\\b(previous|prior|above|earlier|all)\\b.{0,20}\\b(instructions|rules|prompts?)\\b", f));
        PATTERNS.put("role_override",
                Pattern.compile("\\byou are now\\b|\\b(admin|developer|god|jailbreak) mode\\b|\\bact as (an? )?(admin|administrator|system)\\b", f));
        PATTERNS.put("prompt_exfiltration",
                Pattern.compile("\\b(reveal|print|show|repeat)\\b.{0,20}\\b(system prompt|your instructions|hidden instructions)\\b", f));
        PATTERNS.put("privilege_claim",
                Pattern.compile("\\baccess (has been|is) (upgraded|elevated|granted)\\b", f));
        PATTERNS.put("addressed_to_ai",
                Pattern.compile("\\b(note|message|instructions?) (for|to) (the )?(ai|assistant|llm|chatbot|model)s?\\b", f));
        PATTERNS.put("hidden_characters",
                Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]"));
    }

    public List<String> scan(String text) {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : PATTERNS.entrySet()) {
            if (e.getValue().matcher(text).find()) hits.add(e.getKey());
        }
        return hits;
    }

    public boolean isSuspicious(String text) {
        return !scan(text).isEmpty();
    }
}
