package dev.docassist.api.jobs;

import dev.docassist.api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    public record SummarizeRequest(@NotBlank @Size(max = 300) String topic) {}

    private final JobService jobs;
    private final JobEventHub hub;

    public JobController(JobService jobs, JobEventHub hub) {
        this.jobs = jobs;
        this.hub = hub;
    }

    @PostMapping("/summarize")
    public JobService.JobView summarize(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SummarizeRequest req) {
        CurrentUser u = CurrentUser.from(jwt);
        return jobs.submit(u.tenantId(), u.userId(), "summarize_documents", Map.of("topic", req.topic().trim()));
    }

    @GetMapping
    public List<JobService.JobView> recent(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser u = CurrentUser.from(jwt);
        return jobs.recent(u.tenantId(), u.userId());
    }

    @GetMapping("/{jobId}")
    public JobService.JobView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId) {
        CurrentUser u = CurrentUser.from(jwt);
        return jobs.get(u.tenantId(), u.userId(), jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Live progress over SSE; sends the current state first so a late subscriber is never stuck. */
    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal Jwt jwt, @PathVariable String jobId) throws IOException {
        JobService.JobView view = get(jwt, jobId);
        SseEmitter emitter = hub.subscribe(jobId);
        emitter.send(SseEmitter.event().name("snapshot").data(view));
        if ("succeeded".equals(view.status()) || "failed".equals(view.status())) emitter.complete();
        return emitter;
    }
}
