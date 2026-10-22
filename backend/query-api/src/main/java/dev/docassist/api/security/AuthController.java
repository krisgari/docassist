package dev.docassist.api.security;

import dev.docassist.common.config.DocassistProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Demo sign-in: lets the UI switch between seeded employees to show permission-aware answers side by side.
 * Disabled with DOCASSIST_DEMO_LOGIN_ENABLED=false, where tokens come from the customer's IdP instead.
 */
@RestController
public class AuthController {

    public record DemoUser(String userId, String name, String title, List<String> groups) {}

    public record LoginRequest(String userId) {}

    public record LoginResponse(String token, DemoUser user) {}

    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;
    private final String tenant;
    private final boolean demoEnabled;
    private final long ttlMinutes;

    public AuthController(JdbcTemplate jdbc, JwtEncoder encoder, DocassistProperties props,
                          @Value("${docassist.auth.demo-login-enabled}") boolean demoEnabled,
                          @Value("${docassist.auth.token-ttl-minutes}") long ttlMinutes) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tenant = props.getTenant();
        this.demoEnabled = demoEnabled;
        this.ttlMinutes = ttlMinutes;
    }

    @GetMapping("/auth/demo-users")
    public List<DemoUser> demoUsers() {
        requireDemo();
        return jdbc.query("""
                SELECT u.user_id, u.display_name, u.title,
                       coalesce(array_agg(g.group_id ORDER BY g.group_id) FILTER (WHERE g.group_id IS NOT NULL), '{}') AS groups
                FROM app_user u LEFT JOIN group_member g ON g.tenant_id = u.tenant_id AND g.user_id = u.user_id
                WHERE u.tenant_id = ? GROUP BY u.user_id, u.display_name, u.title ORDER BY u.user_id
                """, (rs, n) -> new DemoUser(rs.getString(1), rs.getString(2), rs.getString(3),
                List.of((String[]) rs.getArray(4).getArray())), tenant);
    }

    @PostMapping("/auth/demo-login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        requireDemo();
        DemoUser user = demoUsers().stream().filter(u -> u.userId().equals(req.userId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("docassist-demo")
                .subject(user.userId())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(ttlMinutes)))
                .claim("tenant", tenant)
                .claim("name", user.name())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new LoginResponse(token, user);
    }

    /** Groups are deliberately not in the token: they are resolved fresh (short cache) on each request. */
    @GetMapping("/api/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt);
        return Map.of("userId", u.userId(), "name", u.name(), "tenant", u.tenantId());
    }

    private void requireDemo() {
        if (!demoEnabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
