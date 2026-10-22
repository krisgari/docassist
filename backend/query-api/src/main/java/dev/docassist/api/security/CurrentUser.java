package dev.docassist.api.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** Identity extracted from a verified token. */
public record CurrentUser(String tenantId, String userId, String name) {

    public static CurrentUser from(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("token has no tenant claim");
        }
        String name = jwt.getClaimAsString("name");
        return new CurrentUser(tenant, jwt.getSubject(), name == null ? jwt.getSubject() : name);
    }
}
