package dev.suvansh.ledger.account;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

import org.springframework.http.HttpStatus;

import dev.suvansh.ledger.common.ProblemException;

/**
 * Where the previous page of an account's history stopped: the created_at and id of its last
 * posting. The next page is everything strictly older. Not signed: a forged cursor can only move
 * the caller around their own account's history.
 */
record PostingCursor(Instant createdAt, long postingId) {

    private static final char SEPARATOR = '|';

    /** The opaque, URL-safe form of this cursor, returned to clients as {@code nextCursor}. */
    String encode() {
        String text = createdAt + String.valueOf(SEPARATOR) + postingId;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Parses a cursor produced by {@link #encode()}; any other input is a 400
     * {@link ProblemException}. The argument must not be null.
     */
    static PostingCursor decode(String toDecode) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(toDecode), StandardCharsets.UTF_8);
            int separator = text.indexOf(SEPARATOR);
            if (separator < 0) {
                throw invalid();
            }
            return new PostingCursor(
                    Instant.parse(text.substring(0, separator)),
                    Long.parseLong(text.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeException e) {
            // Bad Base64, a non-numeric id (NumberFormatException) or an unparseable timestamp.
            throw invalid();
        }
    }

    private static ProblemException invalid() {
        return new ProblemException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor",
                "The cursor must be the nextCursor value of a previous response.");
    }
}