package dev.suvansh.ledger.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.suvansh.ledger.account.AccountNotFoundException;
import dev.suvansh.ledger.account.AccountType;
import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.MoneyDto;
import dev.suvansh.ledger.common.ProblemException;

/**
 * Moves money between two accounts as one double-entry journal entry.
 *
 * <p>Contract the tests in this package assume:
 * <ul>
 *   <li>Insufficient funds is not an exception: the transfer is stored as REJECTED and returned.</li>
 *   <li>Replaying a key with the same request returns the originally stored {@link Transfer}
 *       (same id, same status) without moving money again.</li>
 *   <li>Replaying a key with a different request throws a {@code ProblemException} with status 409.</li>
 * </ul>
 */
@Service
public class TransferService {

    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";
    private static final String TRANSFER_COMMITTED = "TransferCommitted";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    TransferService(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    private record LockedAccount(UUID id, AccountType type, long balanceMinor, String currency) {}

    private record StoredTransfer(UUID id, TransferStatus status, String requestHash) {}

    /** The outbox event payload. Field names are the published JSON field names; do not rename them casually. */
    private record TransferCommitted(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                     UUID transferId, UUID fromAccountId, UUID toAccountId, MoneyDto amount) {}

    public Transfer transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, Money amount) {
        validate(fromAccountId, toAccountId, amount);

        String hash = requestHash(fromAccountId, toAccountId, amount);

        Transfer existing = findByKey(idempotencyKey, hash);
        if (existing != null) {
            return existing;
        }

        try {
            return tx.execute(txStatus -> book(idempotencyKey, hash, fromAccountId, toAccountId, amount));
        } catch (DuplicateKeyException e) {
            // A parallel request with the same key committed first: the UNIQUE constraint on
            // idempotency_key made us lose, and our transaction has rolled back. The winner's row
            // is committed by now, so replay it (or raise the 409 if its request differed).
            Transfer winner = findByKey(idempotencyKey, hash);
            if (winner == null) {
                throw e; // some other unique constraint fired; not ours to hide
            }
            return winner;
        }
    }

    /** Checks that need no database, so they run before any lookup, transaction or lock. */
    private static void validate(UUID from, UUID to, Money amount) {
        if (from.equals(to)) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "same-account",
                    "Source and destination accounts are the same",
                    "The source and destination accounts must be different.");
        }
        if (amount.minorUnits() <= 0) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "invalid-amount",
                    "Invalid transfer amount",
                    "The transfer amount must be greater than zero.");
        }
    }

    /**
     * Fingerprint of the request, stored with the key to detect the same key being reused for a
     * different request. Every field of the request must be part of the canonical string, or a
     * change to that field would wrongly replay the old result.
     */
    private static String requestHash(UUID from, UUID to, Money amount) {
        String canonical = from + "|" + to + "|" + amount.minorUnits();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is guaranteed to exist on every JVM
        }
    }

    /** Runs inside the caller's transaction: lock, decide, insert transfer, and (if funded) post and update balances. */
    private Transfer book(String idempotencyKey, String requestHash, UUID from, UUID to, Money amount) {
        // Lock both accounts in one statement, always in ascending id order, so opposing
        // transfers cannot deadlock. The result is therefore sorted by id, not source-first.
        List<LockedAccount> locked = jdbc.sql("""
                        SELECT id, type, balance_minor, currency FROM accounts
                        WHERE id IN (:from, :to) ORDER BY id FOR UPDATE
                        """)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new LockedAccount(
                        rs.getObject("id", UUID.class),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getLong("balance_minor"),
                        rs.getString("currency")))
                .list();

        LockedAccount source = find(locked, from);
        LockedAccount destination = find(locked, to);

        if (!source.currency().equals(destination.currency())) {
            throw new ProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "currency-mismatch",
                    "Currency mismatch",
                    "The source and destination accounts must have the same currency.");
        }

        // SYSTEM accounts may go negative, so they are never rejected for funds.
        boolean enough = source.type() == AccountType.SYSTEM || source.balanceMinor() >= amount.minorUnits();
        TransferStatus status = enough ? TransferStatus.COMMITTED : TransferStatus.REJECTED;

        // The transfers row goes first: if the idempotency key is a duplicate, this insert fails
        // before anything else is written.
        UUID transferId = UUID.randomUUID();
        String rejectReason = status == TransferStatus.REJECTED ? INSUFFICIENT_FUNDS : null;
        jdbc.sql("""
                        INSERT INTO transfers (id, idempotency_key, request_hash, from_account_id, to_account_id,
                                               amount_minor, status, reject_reason)
                        VALUES (:id, :key, :hash, :from, :to, :amount, :status, :reason)
                        """)
                .param("id", transferId)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("from", from)
                .param("to", to)
                .param("amount", amount.minorUnits())
                .param("status", status.name())
                .param("reason", rejectReason)
                .update();

        if (status == TransferStatus.COMMITTED) {
            UUID entryId = UUID.randomUUID();
            jdbc.sql("INSERT INTO journal_entries (id, transfer_id) VALUES (:entryId, :transferId)")
                    .param("entryId", entryId)
                    .param("transferId", transferId)
                    .update();

            insertPosting(entryId, from, amount.negate());
            insertPosting(entryId, to, amount);

            adjustBalance(from, amount.negate());
            adjustBalance(to, amount);

            // Same transaction as the transfer, so the event exists if and only if the transfer committed.
            insertOutboxEvent(transferId, from, to, amount, source.currency());
        }

        return new Transfer(transferId, status);
    }

    /** The locked row for this id; a missing row means the account does not exist. */
    private static LockedAccount find(List<LockedAccount> locked, UUID id) {
        return locked.stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    private void insertPosting(UUID entryId, UUID accountId, Money amount) {
        jdbc.sql("""
                        INSERT INTO postings (journal_entry_id, account_id, amount_minor)
                        VALUES (:entryId, :accountId, :amount)
                        """)
                .param("entryId", entryId)
                .param("accountId", accountId)
                .param("amount", amount.minorUnits())
                .update();
    }

    private void adjustBalance(UUID accountId, Money delta) {
        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :delta WHERE id = :accountId")
                .param("delta", delta.minorUnits())
                .param("accountId", accountId)
                .update();
    }

    /**
     * Returns the stored result if this key was already used for the same request, null if the key is
     * new, and throws a 409 if the key was used for a different request.
     *
     * <p>Keys are global, not per client. Revisit once there is authentication.
     */
    private Transfer findByKey(String idempotencyKey, String requestHash) {
        StoredTransfer stored = jdbc.sql("SELECT id, status, request_hash FROM transfers WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new StoredTransfer(
                        rs.getObject("id", UUID.class),
                        TransferStatus.valueOf(rs.getString("status")),
                        rs.getString("request_hash")))
                .optional()
                .orElse(null);

        if (stored == null) {
            return null;
        }
        if (!stored.requestHash().equals(requestHash)) {
            throw new ProblemException(
                    HttpStatus.CONFLICT,
                    "idempotency-key-reused",
                    "Idempotency key reused",
                    "This Idempotency-Key was already used with a different request.");
        }
        return new Transfer(stored.id(), stored.status());
    }

    private void insertOutboxEvent(UUID transferId, UUID from, UUID to, Money amount, String currency) {
        UUID eventId = UUID.randomUUID();
        TransferCommitted event = new TransferCommitted(eventId, TRANSFER_COMMITTED, 1, Instant.now(),
                transferId, from, to, MoneyDto.of(amount, currency));

        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // a plain record always serializes
        }

        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_id, event_type, payload)
                        VALUES (:id, :aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", eventId)
                .param("aggregateId", transferId)
                .param("eventType", TRANSFER_COMMITTED)
                .param("payload", payload)
                .update();
    }
}
