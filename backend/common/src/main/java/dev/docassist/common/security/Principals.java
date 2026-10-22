package dev.docassist.common.security;

import dev.docassist.common.util.Hashing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** The set of ACL principals a user matches: their user id, each group, and the org-wide public principal. */
public record Principals(String tenantId, String userId, List<String> groups, List<String> all) {

    public static final String PUBLIC = "org:public";

    public static Principals of(String tenantId, String userId, Collection<String> groups) {
        List<String> all = new ArrayList<>();
        all.add("user:" + userId);
        for (String g : new TreeSet<>(groups)) all.add("group:" + g);
        all.add(PUBLIC);
        return new Principals(tenantId, userId, List.copyOf(new TreeSet<>(groups)), List.copyOf(all));
    }

    /** Users with identical group sets share a fingerprint, so they can share permission-safe cache entries. */
    public String aclFingerprint() {
        return Hashing.shortHash(tenantId + "|" + String.join(",", groups) + "|" + PUBLIC);
    }

    public boolean canSee(Collection<String> acl) {
        for (String p : acl) {
            if (p.equals("user:" + userId) || p.equals(PUBLIC)) return true;
            if (p.startsWith("group:") && groups.contains(p.substring(6))) return true;
        }
        return false;
    }
}
