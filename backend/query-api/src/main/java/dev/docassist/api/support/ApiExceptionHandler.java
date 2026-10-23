package dev.docassist.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/** Errors leave the API as a stable shape with a correlation id; stack traces stay in the logs. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request",
                "message", e.getBindingResult().getAllErrors().get(0).getDefaultMessage()));
    }

    /** Bad tool arguments from the agent (missing query, malformed date) are the caller's error, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request",
                "message", e.getMessage() == null ? "invalid request" : e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getStatusCode().toString(),
                "message", e.getReason() == null ? "" : e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        log.error("unhandled error [{}]", id, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal_error", "message", "Something went wrong. Reference " + id));
    }
}
