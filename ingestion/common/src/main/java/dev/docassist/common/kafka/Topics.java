package dev.docassist.common.kafka;

/**
 * Kafka topic contract. Keys are always "{tenant}:{entity id}" so all events for one document (or job) land on
 * one partition and are processed in order — an edit can never overtake a delete.
 */
public final class Topics {
    private Topics() {}

    /** Document upserted / deleted. Producer: connectors. Consumer: ingest pipeline. */
    public static final String DOC_EVENTS = "doc.events";
    /** ACL changed on a document. Separate topic so revocations are never stuck behind a bulk re-index. */
    public static final String ACL_EVENTS = "acl.events";
    /** Async tool job requested. Producer: query plane (Python). Consumer: job worker. */
    public static final String JOB_REQUESTS = "jobs.requests";
    /** Job progress / completion. Producer: job worker. Consumer: query plane (SSE fan-out). */
    public static final String JOB_EVENTS = "jobs.events";

    public static String key(String tenant, String id) {
        return tenant + ":" + id;
    }
}
