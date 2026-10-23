package dev.docassist.common.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Explicit JSON (de)serialization for Kafka payloads: no type headers, schema lives in {@link Events}. */
@Component
public class Json {
    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            // Non-retryable: routed straight to the dead-letter topic by the consumer error handler.
            throw new MalformedEventException("cannot parse " + type.getSimpleName(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
