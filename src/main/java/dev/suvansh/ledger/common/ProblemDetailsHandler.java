package dev.suvansh.ledger.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into an RFC 9457 {@code application/problem+json} response.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives us Spring's built-in
 * mapping for MVC exceptions (malformed JSON, wrong method, unsupported media type, ...)
 * as Problem Details for free; we override the few that need more, and add handlers
 * for our own {@link ProblemException} and for anything unexpected.
 */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<Object> handleProblem(ProblemException ex, WebRequest request) {
        ProblemDetail problem = problem(ex.getStatus(), ex.getSlug(), ex.getTitle(), ex.getMessage());
        return handleExceptionInternal(ex, problem, new HttpHeaders(), ex.getStatus(), request);
    }

    /** Safety net: log the real cause, tell the client nothing about it. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal server error", "An unexpected error occurred.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** 400 with a per-field list under {@code errors}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "Validation failed", "One or more fields are invalid.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** A missing Idempotency-Key is 428 Precondition Required; any other missing header stays 400. */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing
                && IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(missing.getHeaderName())) {
            ProblemDetail problem = problem(HttpStatus.PRECONDITION_REQUIRED, "idempotency-key-required",
                    "Idempotency-Key header required",
                    "This request must include an " + IDEMPOTENCY_KEY_HEADER + " header.");
            return handleExceptionInternal(ex, problem, headers, HttpStatus.PRECONDITION_REQUIRED, request);
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        return Problems.of(status, slug, title, detail);
    }

    public record FieldViolation(String field, String message) {}
}
