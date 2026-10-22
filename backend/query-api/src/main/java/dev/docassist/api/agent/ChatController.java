package dev.docassist.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.docassist.api.jobs.JobService;
import dev.docassist.api.security.CurrentUser;
import dev.docassist.api.support.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * POST /api/chat streams Server-Sent Events: "status" and "tool" while the agent works, "job" when it starts a
 * background job, then one "answer" (or "error"). Each request runs on a virtual thread.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    public record ChatRequest(@NotBlank @Size(max = 2000) String question, List<AgentModels.Turn> history) {}

    private final AgentService agent;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AgentService agent, RateLimiter rateLimiter, ObjectMapper mapper) {
        this.agent = agent;
        this.rateLimiter = rateLimiter;
        this.mapper = mapper;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChatRequest req) {
        CurrentUser user = CurrentUser.from(jwt);
        if (!rateLimiter.allow(user.tenantId(), user.userId())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many questions in the last minute.");
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> {
            try {
                AgentModels.Result result = agent.run(user, req.question().trim(), req.history(), new AgentModels.Events() {
                    @Override
                    public void status(String stage, String message) {
                        send(emitter, "status", Map.of("stage", stage, "message", message));
                    }

                    @Override
                    public void tool(AgentModels.ToolTrace trace) {
                        send(emitter, "tool", trace);
                    }

                    @Override
                    public void job(JobService.JobView job) {
                        send(emitter, "job", job);
                    }
                });
                send(emitter, "answer", result);
                emitter.complete();
            } catch (Exception e) {
                log.error("chat failed for {}", user.userId(), e);
                send(emitter, "error", Map.of("message", "Something went wrong answering that. Please try again."));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            log.debug("client went away: {}", e.getMessage());
        }
    }
}
