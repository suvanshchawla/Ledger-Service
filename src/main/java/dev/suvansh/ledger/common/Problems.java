package dev.suvansh.ledger.common;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problems with this service's {@code urn:ledger:problem:<slug>} type URIs. */
public final class Problems {

    private static final String TYPE_PREFIX = "urn:ledger:problem:";

    private Problems() {}

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + slug));
        problem.setTitle(title);
        return problem;
    }
}
