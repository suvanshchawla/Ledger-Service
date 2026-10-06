package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.suvansh.ledger.PostgresTestConfig;
import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.ProblemException;
import dev.suvansh.ledger.transfer.ParallelRunner.Outcome;

/**
 * Transactional outbox: a committed transfer writes exactly one TransferCommitted event in the same
 * transaction as the transfer itself; nothing else does.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
class TransferOutboxTest {

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    private final String key = "key-" + UUID.randomUUID();

    private record OutboxRow(UUID id, UUID aggregateId, String eventType, String payload, Instant publishedAt) {}

    @Test
    void committedTransferWritesOneEventWithTheDocumentedPayload() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);

        Instant before = Instant.now();
        Transfer transfer = transfers.transfer(key, from, to, Money.ofMinorUnits(2_500));
        Instant after = Instant.now();

        List<OutboxRow> events = eventsFor(transfer.id());
        assertThat(events).hasSize(1);
        OutboxRow event = events.get(0);
        assertThat(event.eventType()).isEqualTo("TransferCommitted");
        assertThat(event.aggregateId()).as("the aggregate is the transfer").isEqualTo(transfer.id());
        assertThat(event.publishedAt()).as("not published until the poller runs").isNull();

        JsonNode payload = json.readTree(event.payload());
        assertThat(payload.get("eventId").asText()).as("consumers deduplicate on this").isEqualTo(event.id().toString());
        assertThat(payload.get("eventType").asText()).isEqualTo("TransferCommitted");
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("transferId").asText()).isEqualTo(transfer.id().toString());
        assertThat(payload.get("fromAccountId").asText()).isEqualTo(from.toString());
        assertThat(payload.get("toAccountId").asText()).isEqualTo(to.toString());
        assertThat(payload.get("amount").get("value").asLong()).isEqualTo(2_500);
        assertThat(payload.get("amount").get("currency").asText()).isEqualTo("CAD");
        assertThat(Instant.parse(payload.get("occurredAt").asText()))
                .isBetween(before.minusSeconds(5), after.plusSeconds(5));

        List<String> fields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("exactly the documented fields: no account names, no extras")
                .containsExactlyInAnyOrder("eventId", "eventType", "schemaVersion", "occurredAt",
                        "transferId", "fromAccountId", "toAccountId", "amount");
    }

    @Test
    void rejectedTransferWritesNoEvent() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 500);
        UUID to = ledger.openCustomer("To", 0);

        Transfer transfer = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));

        assertThat(transfer.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(eventsFor(transfer.id())).isEmpty();
    }

    @Test
    void replayingAKeyDoesNotWriteASecondEvent() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);

        Transfer original = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));
        Transfer replay = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));

        assertThat(replay).isEqualTo(original);
        assertThat(eventsFor(original.id())).hasSize(1);
    }

    @Test
    void fiftyParallelRequestsWithOneKeyWriteExactlyOneEvent() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> transfers.transfer(key, from, to, Money.ofMinorUnits(2_500)));
        }

        List<Outcome<Transfer>> outcomes = ParallelRunner.runTogether(tasks);

        assertThat(outcomes.stream().filter(Outcome::failed).map(Outcome::error).toList()).isEmpty();
        UUID transferId = outcomes.get(0).value().id();
        assertThat(eventsFor(transferId)).hasSize(1);
    }

    @Test
    void eachSeparateTransferWritesItsOwnEvent() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);

        Transfer first = transfers.transfer("key-" + UUID.randomUUID(), from, to, Money.ofMinorUnits(1_000));
        Transfer second = transfers.transfer("key-" + UUID.randomUUID(), from, to, Money.ofMinorUnits(1_000));

        assertThat(eventsFor(first.id())).hasSize(1);
        assertThat(eventsFor(second.id())).hasSize(1);
        assertThat(eventsFor(first.id()).get(0).id()).isNotEqualTo(eventsFor(second.id()).get(0).id());
    }

    @Test
    void aTransferThatFailsValidationLeavesNoEventBehind() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID cad = ledger.openCustomer("Canadian", 1_000, "CAD");
        UUID usd = ledger.openCustomer("American", 0, "USD");

        assertThatThrownBy(() -> transfers.transfer(key, cad, usd, Money.ofMinorUnits(100)))
                .isInstanceOf(ProblemException.class);

        long events = jdbc.sql("SELECT count(*) FROM outbox_events WHERE payload->>'fromAccountId' = :id")
                .param("id", cad.toString()).query(Long.class).single();
        assertThat(events).isZero();
    }

    private List<OutboxRow> eventsFor(UUID transferId) {
        return jdbc.sql("""
                        SELECT id, aggregate_id, event_type, payload::text AS payload, published_at
                        FROM outbox_events WHERE aggregate_id = :transferId
                        """)
                .param("transferId", transferId)
                .query((rs, rowNum) -> {
                    OffsetDateTime published = rs.getObject("published_at", OffsetDateTime.class);
                    return new OutboxRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("aggregate_id", UUID.class),
                            rs.getString("event_type"),
                            rs.getString("payload"),
                            published == null ? null : published.toInstant());
                })
                .list();
    }
}
