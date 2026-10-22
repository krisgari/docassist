package dev.docassist.worker.ingest;

import dev.docassist.common.chunking.FrontMatter;
import dev.docassist.common.kafka.Events;
import dev.docassist.common.kafka.Json;
import dev.docassist.common.kafka.Topics;
import dev.docassist.worker.connector.FilesystemConnector;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;

/**
 * Ingestion consumers. Each uses non-blocking retry topics (doc.events-retry-N) and a dead-letter topic, so a
 * poison document is parked instead of blocking its partition. Malformed payloads and documents with no ACL
 * skip retries entirely: retrying cannot fix them.
 */
@Component
public class IngestConsumers {

    private static final Logger log = LoggerFactory.getLogger(IngestConsumers.class);

    private final IndexWriter writer;
    private final FilesystemConnector connector;
    private final Json json;
    private final MeterRegistry meters;

    public IngestConsumers(IndexWriter writer, FilesystemConnector connector, Json json, MeterRegistry meters) {
        this.writer = writer;
        this.connector = connector;
        this.json = json;
        this.meters = meters;
    }

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 1000, multiplier = 2.0),
            exclude = {Json.MalformedEventException.class, IllegalArgumentException.class},
            dltStrategy = DltStrategy.FAIL_ON_ERROR)
    @KafkaListener(topics = Topics.DOC_EVENTS, groupId = "ingest", concurrency = "3")
    public void onDocEvent(ConsumerRecord<String, String> record) throws IOException {
        Events.DocEvent e = json.read(record.value(), Events.DocEvent.class);
        IndexWriter.Outcome outcome;
        if (e.type() == Events.DocEventType.DELETED) {
            outcome = writer.delete(e.tenantId(), e.docId());
        } else {
            Path file = connector.root().resolve(e.contentRef()).normalize();
            if (!file.startsWith(connector.root())) {
                throw new IllegalArgumentException("content ref escapes connector root: " + e.contentRef());
            }
            String raw;
            try {
                raw = Files.readString(file);
            } catch (NoSuchFileException gone) {
                log.info("{} vanished before ingest; the next scan will emit a delete", e.docId());
                return;
            }
            outcome = writer.upsert(e.tenantId(), FrontMatter.parse(raw), e.contentHash(), e.sourceVersion());
        }
        meters.counter("docassist.ingest.events", "outcome", outcome.name().toLowerCase()).increment();
    }

    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 500, multiplier = 2.0),
            exclude = {Json.MalformedEventException.class})
    @KafkaListener(topics = Topics.ACL_EVENTS, groupId = "acl-sync", concurrency = "2")
    public void onAclEvent(ConsumerRecord<String, String> record) {
        Events.AclEvent e = json.read(record.value(), Events.AclEvent.class);
        int n = writer.updateAcl(e.tenantId(), e.docId(), e.principals());
        long lagMs = System.currentTimeMillis() - e.occurredAt().toEpochMilli();
        meters.timer("docassist.acl.propagation").record(java.time.Duration.ofMillis(Math.max(0, lagMs)));
        log.info("acl updated for {} -> {} ({} ms after change, rows={})", e.docId(), e.principals(), lagMs, n);
    }

    @DltHandler
    public void deadLetter(ConsumerRecord<String, String> record,
                           @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
        meters.counter("docassist.ingest.dead_letters", "topic", record.topic()).increment();
        log.error("dead-lettered key={} topic={} error={}", record.key(), record.topic(), error);
    }
}
