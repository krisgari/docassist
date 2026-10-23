package dev.docassist.worker.connector;

import dev.docassist.common.chunking.FrontMatter;
import dev.docassist.common.config.DocassistProperties;
import dev.docassist.common.kafka.Events;
import dev.docassist.common.kafka.Json;
import dev.docassist.common.kafka.Topics;
import dev.docassist.common.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Stand-in for a SharePoint / Drive / Confluence connector: polls a folder of markdown files and emits change
 * events. The shape is the same as a real connector's delta sync: compare what the source has with what we last
 * emitted, then publish UPSERTED, DELETED, or (fast path) ACL-only changes.
 */
@Component
public class FilesystemConnector {

    private static final Logger log = LoggerFactory.getLogger(FilesystemConnector.class);

    private record Seen(String contentHash, String aclHash) {}

    public record ScanReport(Instant at, int files, int upserts, int aclChanges, int deletes, List<String> errors) {}

    private final Path root;
    private final String tenant;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Json json;
    private final Map<String, Seen> published = new HashMap<>();
    private final AtomicReference<ScanReport> lastReport = new AtomicReference<>();
    private boolean primed = false;

    public FilesystemConnector(@Value("${docassist.connector.root}") String root, DocassistProperties props,
                               JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, Json json) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.tenant = props.getTenant();
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.json = json;
    }

    public Path root() {
        return root;
    }

    public ScanReport lastReport() {
        return lastReport.get();
    }

    @Scheduled(fixedDelayString = "${docassist.connector.scan-interval-ms}", initialDelay = 3000)
    public synchronized ScanReport scan() {
        if (!primed) primeFromIndex();
        int files = 0, upserts = 0, acl = 0, deletes = 0;
        List<String> errors = new ArrayList<>();
        Set<String> present = new HashSet<>();

        List<Path> paths;
        try (Stream<Path> s = Files.walk(root)) {
            paths = s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            log.error("connector cannot read {}: {}", root, e.getMessage());
            return record(new ScanReport(Instant.now(), 0, 0, 0, 0, List.of("cannot read root: " + e.getMessage())));
        }

        for (Path p : paths) {
            files++;
            try {
                String raw = Files.readString(p);
                FrontMatter fm = FrontMatter.parse(raw);
                present.add(fm.id());
                String contentHash = Hashing.sha256(fm.title() + "\n" + fm.status() + "\n" + fm.sensitivity()
                        + "\n" + fm.type() + "\n" + fm.body());
                String aclHash = Hashing.sha256(String.join(",", fm.acl().stream().sorted().toList()));
                Seen prev = published.get(fm.id());
                String relative = root.relativize(p).toString();
                long version = Files.getLastModifiedTime(p).toMillis();

                if (prev == null || !prev.contentHash().equals(contentHash)) {
                    send(Topics.DOC_EVENTS, fm.id(), new Events.DocEvent(UUID.randomUUID().toString(),
                            Events.DocEventType.UPSERTED, tenant, fm.id(), relative, contentHash, version, Instant.now()));
                    upserts++;
                } else if (!prev.aclHash().equals(aclHash)) {
                    send(Topics.ACL_EVENTS, fm.id(), new Events.AclEvent(UUID.randomUUID().toString(), tenant,
                            fm.id(), fm.acl(), Instant.now()));
                    acl++;
                }
                published.put(fm.id(), new Seen(contentHash, aclHash));
            } catch (Exception e) {
                // One bad file never blocks the rest of the sync.
                errors.add(root.relativize(p) + ": " + e.getMessage());
                log.warn("connector skipped {}: {}", p, e.getMessage());
            }
        }

        for (String docId : new ArrayList<>(published.keySet())) {
            if (!present.contains(docId)) {
                send(Topics.DOC_EVENTS, docId, new Events.DocEvent(UUID.randomUUID().toString(),
                        Events.DocEventType.DELETED, tenant, docId, null, null, System.currentTimeMillis(), Instant.now()));
                published.remove(docId);
                deletes++;
            }
        }
        if (upserts + acl + deletes > 0) {
            log.info("connector scan: files={} upserts={} acl={} deletes={} errors={}", files, upserts, acl, deletes, errors.size());
        }
        return record(new ScanReport(Instant.now(), files, upserts, acl, deletes, errors));
    }

    /** On startup, compare against what is already indexed so a restart doesn't re-ingest everything. */
    private void primeFromIndex() {
        jdbc.query("SELECT doc_id, content_hash, acl_principals FROM document WHERE tenant_id = ? AND status <> 'deleted'",
                rs -> {
                    String[] acl = (String[]) rs.getArray("acl_principals").getArray();
                    String aclHash = Hashing.sha256(String.join(",", Arrays.stream(acl).sorted().toList()));
                    published.put(rs.getString("doc_id"), new Seen(rs.getString("content_hash"), aclHash));
                }, tenant);
        primed = true;
        log.info("connector primed with {} indexed documents from {}", published.size(), root);
    }

    private void send(String topic, String docId, Object event) {
        kafka.send(topic, Topics.key(tenant, docId), json.write(event));
    }

    private ScanReport record(ScanReport r) {
        lastReport.set(r);
        return r;
    }
}
