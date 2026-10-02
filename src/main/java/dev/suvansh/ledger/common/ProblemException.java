package dev.suvansh.ledger.common;

import org.springframework.http.HttpStatus;

/**
 * A business-level failure that should reach the client as an RFC 9457 Problem
 * Details response (404 account not found, 409 idempotency conflict, 422
 * insufficient funds, ...). {@link ProblemDetailsHandler} turns it into JSON.
 *
 * <p>Unchecked, so it can be thrown from deep inside a service without every
 * caller declaring it. It is also what makes Spring roll back a
 * {@code @Transactional} method by default.
 */
public class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String slug;
    private final String title;

    /**
     * @param slug  short stable identifier, e.g. {@code insufficient-funds}; becomes the problem {@code type} URI
     * @param title short human-readable summary, the same for every occurrence of this problem
     * @param detail explanation specific to this occurrence
     */
    public ProblemException(HttpStatus status, String slug, String title, String detail) {
        super(detail);
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }
}
