package dev.docassist.common.kafka;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Event payloads. Large content travels by reference (path / URI + hash), never inline. */
public final class Events {
    private Events() {}

    public enum DocEventType { UPSERTED, DELETED }

    public record DocEvent(
            String eventId,
            DocEventType type,
            String tenantId,
            String docId,
            String contentRef,
            String contentHash,
            long sourceVersion,
            Instant occurredAt) {}

    public record AclEvent(
            String eventId,
            String tenantId,
            String docId,
            List<String> principals,
            Instant occurredAt) {}

    public record JobRequested(
            String jobId,
            String tenantId,
            String userId,
            String jobType,
            Map<String, Object> args,
            Instant requestedAt) {}

    public record JobEvent(
            String jobId,
            String tenantId,
            String status,
            int progress,
            String note,
            Instant occurredAt) {}
}
