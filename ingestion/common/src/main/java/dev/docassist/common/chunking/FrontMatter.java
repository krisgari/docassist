package dev.docassist.common.chunking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the metadata header of a corpus file. The filesystem connector stands in for SharePoint / Drive /
 * Confluence; in a real connector these fields come from the source system's API instead of a header.
 */
public record FrontMatter(
        String id,
        String title,
        String source,
        String url,
        String owner,
        String type,
        String sensitivity,
        String status,
        List<String> acl,
        Instant modified,
        String body) {

    public static FrontMatter parse(String raw) {
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("missing front matter");
        }
        int end = text.indexOf("\n---", 4);
        if (end < 0) {
            throw new IllegalArgumentException("unterminated front matter");
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : text.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            kv.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        String body = text.substring(text.indexOf('\n', end + 1) + 1);

        List<String> acl = new ArrayList<>();
        for (String p : kv.getOrDefault("acl", "").split(",")) {
            String principal = p.trim();
            if (!principal.isEmpty()) acl.add(principal);
        }
        if (acl.isEmpty()) {
            throw new IllegalArgumentException("document has no ACL; refusing to index (fail closed)");
        }
        String id = require(kv, "id");
        return new FrontMatter(
                id,
                require(kv, "title"),
                kv.getOrDefault("source", "filesystem"),
                kv.getOrDefault("url", "file://" + id),
                kv.getOrDefault("owner", "unknown"),
                kv.getOrDefault("type", "document"),
                kv.getOrDefault("sensitivity", "internal"),
                kv.getOrDefault("status", "active"),
                List.copyOf(acl),
                Instant.parse(require(kv, "modified")),
                body);
    }

    private static String require(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("front matter missing '" + key + "'");
        }
        return v;
    }
}
