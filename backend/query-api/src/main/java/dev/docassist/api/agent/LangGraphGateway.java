package dev.docassist.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Forwards a chat turn to the LangGraph agent service and relays its Server-Sent Events to the browser unchanged.
 * The gateway keeps the edge concerns (authentication, rate limiting, the browser-facing contract); the agent
 * service only orchestrates. The user's JWT is forwarded so every tool call the agent makes back into this API is
 * permission-checked as that user.
 */
@Component
public class LangGraphGateway {

    private static final Logger log = LoggerFactory.getLogger(LangGraphGateway.class);
    private static final Set<String> RELAYED = Set.of("status", "tool", "job", "answer", "error");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper;
    private final String agentUrl;
    private final String serviceToken;

    public LangGraphGateway(ObjectMapper mapper,
                            @Value("${docassist.agent.url}") String agentUrl,
                            @Value("${docassist.agent.internal-token}") String serviceToken) {
        this.mapper = mapper;
        this.agentUrl = agentUrl.replaceAll("/+$", "");
        this.serviceToken = serviceToken;
    }

    /** Blocking; call from a virtual thread. Always ends the stream with "answer" or "error". */
    public void relay(String userToken, String question, List<AgentModels.Turn> history, SseEmitter emitter) {
        boolean finished = false;
        try {
            String body = mapper.writeValueAsString(Map.of("question", question, "history", history == null ? List.of() : history));
            HttpRequest req = HttpRequest.newBuilder(URI.create(agentUrl + "/v1/chat"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + userToken)
                    .header("X-Internal-Token", serviceToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() != 200) {
                log.error("agent service returned {}", resp.statusCode());
                send(emitter, "error", "{\"message\":\"The assistant service returned an error. Please try again.\"}");
                return;
            }
            try (Stream<String> lines = resp.body()) {
                String event = "message";
                StringBuilder data = new StringBuilder();
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    } else if (line.isEmpty()) {
                        if (!data.isEmpty() && RELAYED.contains(event)) {
                            send(emitter, event, data.toString());
                            if (event.equals("answer") || event.equals("error")) finished = true;
                        }
                        event = "message";
                        data.setLength(0);
                    }
                }
            }
            if (!finished) {
                send(emitter, "error", "{\"message\":\"The assistant stopped before finishing. Please try again.\"}");
            }
        } catch (IOException e) {
            log.error("agent service unreachable at {}: {}", agentUrl, e.getMessage());
            send(emitter, "error", "{\"message\":\"The assistant service is unavailable. Please try again in a minute.\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void send(SseEmitter emitter, String event, String json) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json));
        } catch (IOException | IllegalStateException e) {
            log.debug("client went away: {}", e.getMessage());
        }
    }
}
