package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.suvansh.ledger.PostgresTestConfig;
import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.ProblemException;
import dev.suvansh.ledger.transfer.ParallelRunner.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One idempotency key produces at most one journal entry. Do not weaken these to make them pass.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
class TransferIdempotencyTest {

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcClient jdbc;

    @Test
    void fiftyParallelRequestsWithTheSameKeyMoveMoneyExactlyOnce() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        String key = "key-" + UUID.randomUUID();
        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> transfers.transfer(key, from, to, Money.ofMinorUnits(2_500)));
        }

        List<Outcome<Transfer>> outcomes = ParallelRunner.runTogether(tasks);

        assertThat(outcomes.stream().filter(Outcome::failed).map(Outcome::error).toList())
                .as("no request may throw").isEmpty();
        Transfer first = outcomes.get(0).value();
        assertThat(first.status()).isEqualTo(TransferStatus.COMMITTED);
        assertThat(outcomes).as("every caller gets the same stored result")
                .allSatisfy(o -> assertThat(o.value()).isEqualTo(first));
        assertThat(ledger.transfersWithKey(key)).isEqualTo(1);
        assertThat(ledger.journalEntriesForKey(key)).isEqualTo(1);
        assertThat(ledger.postingsForKey(key)).isEqualTo(2);
        assertThat(ledger.balanceOf(from)).isEqualTo(7_500);
        assertThat(ledger.balanceOf(to)).isEqualTo(2_500);
        ledger.assertInvariantsHold();
    }

    @Test
    void sequentialReplayReturnsTheOriginalResultWithoutMovingMoneyAgain() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        String key = "key-" + UUID.randomUUID();

        Transfer original = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));
        Transfer replay = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));

        assertThat(replay).isEqualTo(original);
        assertThat(ledger.journalEntriesForKey(key)).isEqualTo(1);
        assertThat(ledger.balanceOf(from)).isEqualTo(9_000);
        assertThat(ledger.balanceOf(to)).isEqualTo(1_000);
        ledger.assertInvariantsHold();
    }

    @Test
    void sameKeyWithADifferentAmountIsRejectedWithConflict() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        String key = "key-" + UUID.randomUUID();
        transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));

        assertThatThrownBy(() -> transfers.transfer(key, from, to, Money.ofMinorUnits(2_000)))
                .isInstanceOfSatisfying(ProblemException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(ledger.journalEntriesForKey(key)).as("still only the first request").isEqualTo(1);
        assertThat(ledger.balanceOf(from)).isEqualTo(9_000);
        ledger.assertInvariantsHold();
    }

    @Test
    void sameKeyWithADifferentDestinationIsRejectedWithConflict() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        UUID other = ledger.openCustomer("Other", 0);
        String key = "key-" + UUID.randomUUID();
        transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));

        assertThatThrownBy(() -> transfers.transfer(key, from, other, Money.ofMinorUnits(1_000)))
                .isInstanceOfSatisfying(ProblemException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(ledger.balanceOf(other)).isZero();
        ledger.assertInvariantsHold();
    }

    @Test
    void replayingARejectedTransferReturnsTheSameRejectionAndBooksNothing() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 500);
        UUID to = ledger.openCustomer("To", 0);
        String key = "key-" + UUID.randomUUID();

        Transfer original = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));
        Transfer replay = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));

        assertThat(original.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(replay).isEqualTo(original);
        assertThat(ledger.transfersWithKey(key)).isEqualTo(1);
        assertThat(ledger.journalEntriesForKey(key)).isZero();
        assertThat(ledger.balanceOf(from)).isEqualTo(500);
        ledger.assertInvariantsHold();
    }
}
