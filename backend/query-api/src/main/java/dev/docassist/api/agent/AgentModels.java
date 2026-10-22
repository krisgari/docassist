package dev.docassist.api.agent;

import dev.docassist.api.jobs.JobService;

import java.util.List;
import java.util.Map;

/** Request / response shapes for the agent and its streaming callbacks. */
public final class AgentModels {
    private AgentModels() {}

    public record Turn(String role, String content) {}

    public record Citation(int n, String docId, String title, String url, String section, String modified,
                           String status, String sourceSystem, String snippet) {}

    public record ToolTrace(String tool, Map<String, Object> input, String summary, long ms, boolean error) {}

    public record GuardrailEvent(String type, String detail) {}

    public record Usage(String model, int inputTokens, int outputTokens, int cacheReadTokens, double costUsd) {}

    public record Result(
            String answer,
            String outcome,                 // answered | not_found | partial | error
            List<Citation> citations,
            List<ToolTrace> trace,
            List<GuardrailEvent> guardrails,
            List<String> droppedByPermissionCheck,
            int passagesConsidered,
            List<Usage> usage,
            double costUsd,
            long latencyMs,
            String auditId,
            String jobId) {}

    /** Streaming callbacks; the controller turns these into SSE events. */
    public interface Events {
        void status(String stage, String message);

        void tool(ToolTrace trace);

        void job(JobService.JobView job);
    }
}
