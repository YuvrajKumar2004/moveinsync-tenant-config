package com.moveinsync.config.server.error;

import com.moveinsync.config.core.model.UnknownSettingException;
import com.moveinsync.config.core.validation.ConfigValidationException;
import com.moveinsync.config.core.validation.InvalidValueException;
import com.moveinsync.config.server.observability.ConfigMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * Maps every failure to a problem response with a stable code. Messages come only from our own
 * exceptions; anything unexpected becomes a generic 500 with the request id, and the details go to the
 * server log (with personal data masked), never to the caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ConfigMetrics metrics;

    public GlobalExceptionHandler(ConfigMetrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(ConfigValidationException.class)
    ResponseEntity<ProblemDetail> validation(ConfigValidationException e) {
        metrics.rejected("validation");
        List<Map<String, String>> v = e.violations().stream()
                .map(x -> Map.of("code", x.code(), "target", PiiRedactor.redact(x.target()), "message", PiiRedactor.redact(x.message())))
                .toList();
        log.info("change rejected: {} violation(s), first={}", v.size(), e.violations().get(0).code());
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "the change was rejected and nothing was published", v);
    }

    @ExceptionHandler(InvalidValueException.class)
    ResponseEntity<ProblemDetail> invalidValue(InvalidValueException e) {
        metrics.rejected("validation");
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", e.getMessage(), null);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        if (e.status() == HttpStatus.FORBIDDEN) {
            metrics.rejected("forbidden");
        } else if (e.status() == HttpStatus.CONFLICT) {
            metrics.rejected("conflict");
        }
        return of(e.status(), e.code(), e.getMessage(), null);
    }

    @ExceptionHandler(UnknownSettingException.class)
    ResponseEntity<ProblemDetail> unknown(UnknownSettingException e) {
        return of(HttpStatus.NOT_FOUND, "UNKNOWN_KEY", e.getMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e) {
        metrics.rejected("forbidden");
        return of(HttpStatus.FORBIDDEN, "FORBIDDEN", "not allowed for this role", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> illegalArgument(IllegalArgumentException e) {
        // only our own domain constructors throw this on bad scope/context input; messages are ours
        return of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> beanValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> v = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("code", "INVALID_FIELD", "target", f.getField(),
                        "message", f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
                .toList();
        return of(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "request fields are invalid", v);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> malformed(Exception e) {
        return of(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "the request could not be read", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException e) {
        return of(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "method not allowed", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e) {
        return of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "use application/json", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noResource(NoResourceFoundException e) {
        return of(HttpStatus.NOT_FOUND, "NOT_FOUND", "no such endpoint", null);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone(AsyncRequestNotUsableException e) {
        // an SSE subscriber disconnected mid-write; nothing to answer
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        metrics.error("unexpected");
        log.error("unexpected error: {}", PiiRedactor.redact(e.toString()), e);
        return of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected error; quote the requestId to support", null);
    }

    private static ResponseEntity<ProblemDetail> of(HttpStatus status, String code, String message, List<?> violations) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            b.header("Retry-After", "1");
        }
        return b.body(ProblemWriter.problem(status, code, message, violations));
    }
}
