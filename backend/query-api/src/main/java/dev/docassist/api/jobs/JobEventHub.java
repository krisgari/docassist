package dev.docassist.api.jobs;

import dev.docassist.common.kafka.Events;
import dev.docassist.common.kafka.Json;
import dev.docassist.common.kafka.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans job progress from Kafka out to browsers. Every API instance consumes jobs.events with its own consumer
 * group (broadcast), and pushes events to the SSE connections it holds for that job.
 */
@Component
public class JobEventHub {

    private static final Logger log = LoggerFactory.getLogger(JobEventHub.class);

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Json json;

    public JobEventHub(Json json) {
        this.json = json;
    }

    public SseEmitter subscribe(String jobId) {
        SseEmitter emitter = new SseEmitter(15 * 60_000L);
        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> {
            List<SseEmitter> list = emitters.get(jobId);
            if (list != null) list.remove(emitter);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    @KafkaListener(topics = Topics.JOB_EVENTS, groupId = "query-api-#{T(java.util.UUID).randomUUID().toString()}",
            properties = {"auto.offset.reset=latest"})
    public void onJobEvent(ConsumerRecord<String, String> record) {
        Events.JobEvent e = json.read(record.value(), Events.JobEvent.class);
        List<SseEmitter> list = emitters.get(e.jobId());
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(json.write(e)));
                if ("succeeded".equals(e.status()) || "failed".equals(e.status())) emitter.complete();
            } catch (IOException | IllegalStateException ex) {
                list.remove(emitter);
                log.debug("dropped job subscriber: {}", ex.getMessage());
            }
        }
    }
}
