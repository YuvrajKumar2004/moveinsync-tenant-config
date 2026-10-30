package com.moveinsync.config.server.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/** Builds RFC 7807 problem responses with a stable error code and the request id, nothing more. */
@Component
public class ProblemWriter {

    private final ObjectMapper mapper;

    public ProblemWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static ProblemDetail problem(HttpStatus status, String code, String message, List<?> violations) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, PiiRedactor.redact(message));
        p.setTitle(status.getReasonPhrase());
        p.setProperty("code", code);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            p.setProperty("requestId", requestId);
        }
        if (violations != null && !violations.isEmpty()) {
            p.setProperty("violations", violations);
        }
        return p;
    }

    /** For filters running outside Spring MVC (authentication failures). */
    public void write(HttpServletResponse res, HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), problem(status, code, message, null));
    }
}
