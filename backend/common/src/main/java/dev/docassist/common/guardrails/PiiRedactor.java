package dev.docassist.common.guardrails;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts credentials and personal identifiers from model output before it reaches the user or the logs. */
public final class PiiRedactor {

    public record Result(String text, List<String> redactedTypes) {
        public boolean changed() {
            return !redactedTypes.isEmpty();
        }
    }

    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put("aws_access_key", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"));
        PATTERNS.put("aws_secret_key", Pattern.compile("(?<![A-Za-z0-9/+])(?=[A-Za-z0-9/+]*[a-z])(?=[A-Za-z0-9/+]*[A-Z])(?=[A-Za-z0-9/+]*[0-9/+])[A-Za-z0-9/+]{40}(?![A-Za-z0-9/+=])"));
        PATTERNS.put("api_key", Pattern.compile("\\b(sk|pk|rk)[-_](live|test|ant|proj)?[-_]?[A-Za-z0-9_-]{20,}\\b"));
        PATTERNS.put("bearer_token", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}=*"));
        PATTERNS.put("private_key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));
        PATTERNS.put("us_ssn", Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"));
    }

    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    public Result redact(String input) {
        String text = input;
        List<String> types = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            if (m.find()) {
                types.add(e.getKey());
                text = m.replaceAll("[REDACTED " + e.getKey() + "]");
            }
        }
        Matcher card = CARD.matcher(text);
        StringBuilder sb = new StringBuilder();
        boolean cardFound = false;
        while (card.find()) {
            String digits = card.group().replaceAll("[ -]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
                card.appendReplacement(sb, "[REDACTED card_number]");
                cardFound = true;
            } else {
                card.appendReplacement(sb, Matcher.quoteReplacement(card.group()));
            }
        }
        card.appendTail(sb);
        if (cardFound) {
            types.add("card_number");
            text = sb.toString();
        }
        return new Result(text, List.copyOf(types));
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
