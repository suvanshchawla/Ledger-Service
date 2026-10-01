package dev.suvansh.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Checks the guarantees the V1 schema itself enforces, independent of any service code. */
@SpringBootTest
@Testcontainers
class SchemaConstraintsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcClient jdbc;

    @Test
    void selfTransferIsRejected() {
        UUID account = insertAccount("CUSTOMER");

        assertThatThrownBy(() -> insertTransfer(account, account))
                .hasMessageContaining("no_self_transfer");
    }

    @Test
    void customerBalanceCannotGoNegative() {
        UUID account = insertAccount("CUSTOMER");

        assertThatThrownBy(() -> jdbc.sql("UPDATE accounts SET balance_minor = -1 WHERE id = ?")
                        .param(account).update())
                .hasMessageContaining("non_negative_customer");
    }

    @Test
    void systemAccountBalanceMayGoNegative() {
        UUID account = insertAccount("SYSTEM");

        int updated = jdbc.sql("UPDATE accounts SET balance_minor = -1 WHERE id = ?")
                .param(account).update();

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void postingsCannotBeUpdatedOrDeleted() {
        long postingId = insertPosting().postingId();

        assertThatThrownBy(() -> jdbc.sql("UPDATE postings SET amount_minor = 999 WHERE id = ?")
                        .param(postingId).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM postings WHERE id = ?")
                        .param(postingId).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void journalEntriesCannotBeUpdatedOrDeleted() {
        UUID entryId = insertPosting().entryId();

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_entries SET created_at = now() WHERE id = ?")
                        .param(entryId).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries WHERE id = ?")
                        .param(entryId).update())
                .hasMessageContaining("append-only");
    }

    private record Inserted(UUID entryId, long postingId) {}

    private Inserted insertPosting() {
        UUID from = insertAccount("SYSTEM");
        UUID to = insertAccount("CUSTOMER");
        UUID transferId = insertTransfer(from, to);
        UUID entryId = UUID.randomUUID();
        jdbc.sql("INSERT INTO journal_entries (id, transfer_id) VALUES (?, ?)")
                .param(entryId).param(transferId).update();
        long postingId = jdbc.sql("""
                        INSERT INTO postings (journal_entry_id, account_id, amount_minor)
                        VALUES (?, ?, 100) RETURNING id
                        """)
                .param(entryId).param(to).query(Long.class).single();
        return new Inserted(entryId, postingId);
    }

    private UUID insertAccount(String type) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, type) VALUES (?, ?)").param(id).param(type).update();
        return id;
    }

    private UUID insertTransfer(UUID from, UUID to) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO transfers
                            (id, idempotency_key, request_hash, from_account_id, to_account_id,
                             amount_minor, status)
                        VALUES (?, ?, 'hash', ?, ?, 100, 'COMMITTED')
                        """)
                .param(id).param(UUID.randomUUID().toString()).param(from).param(to).update();
        return id;
    }
}
