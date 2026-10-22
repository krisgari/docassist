package dev.docassist.api.audit;

import dev.docassist.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Thumbs up / down with a reason. Negative feedback is triaged from the audit trace and fed into the golden set. */
@RestController
public class FeedbackController {

    public record FeedbackRequest(
            @NotNull String auditId,
            @NotNull Integer rating,
            @Pattern(regexp = "wrong|outdated|missing_source|cant_open_source|other") String reason,
            @Size(max = 1000) String comment) {}

    private final JdbcTemplate jdbc;

    public FeedbackController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/feedback")
    public Map<String, Object> feedback(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FeedbackRequest req) {
        CurrentUser u = CurrentUser.from(jwt);
        if (req.rating() != 1 && req.rating() != -1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must be 1 or -1");
        int owned;
        try {
            owned = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE audit_id = ?::uuid AND tenant_id = ? AND user_id = ?",
                    Integer.class, req.auditId(), u.tenantId(), u.userId());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid audit id");
        }
        if (owned == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        jdbc.update("INSERT INTO feedback (tenant_id, audit_id, user_id, rating, reason, comment) VALUES (?, ?::uuid, ?, ?, ?, ?)",
                u.tenantId(), req.auditId(), u.userId(), req.rating(), req.reason(), req.comment());
        return Map.of("ok", true);
    }
}
