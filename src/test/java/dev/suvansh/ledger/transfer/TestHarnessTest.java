package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.suvansh.ledger.LedgerFixture;
import dev.suvansh.ledger.PostgresTestConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Tests the test helpers themselves. A concurrency test is only evidence if the runner really
 * runs things concurrently and the invariant checker really catches violations.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
class TestHarnessTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void fundedAccountsSatisfyEveryInvariant() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID a = ledger.openCustomer("A", 10_000);
        UUID b = ledger.openCustomer("B", 2_500);

        assertThat(ledger.balanceOf(a)).isEqualTo(10_000);
        assertThat(ledger.balanceOf(b)).isEqualTo(2_500);
        assertThat(ledger.balanceOf(ledger.fundingAccount())).isEqualTo(-12_500);
        ledger.assertInvariantsHold();
    }

    @Test
    void checkerDetectsAnUnbalancedJournalEntry() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID a = ledger.openCustomer("A", 0);

        ledger.recordEntry(ledger.fundingAccount(), a, 100, 90);

        assertThatThrownBy(ledger::assertInvariantsHold).isInstanceOf(AssertionError.class)
                .hasMessageContaining("sums to zero");
    }

    @Test
    void checkerDetectsACachedBalanceThatDisagreesWithPostings() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID a = ledger.openCustomer("A", 1_000);

        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + 1 WHERE id = ?").param(a).update();

        assertThatThrownBy(ledger::assertInvariantsHold).isInstanceOf(AssertionError.class)
                .hasMessageContaining("cached balance equals the sum of postings");
    }

    @Test
    void runnerReleasesAllTasksAtTheSameTime() throws Exception {
        // Every task waits for all 20 to arrive; if they ran one at a time this would never complete.
        CountDownLatch allArrived = new CountDownLatch(20);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                allArrived.countDown();
                return allArrived.await(5, TimeUnit.SECONDS);
            });
        }

        var outcomes = ParallelRunner.runTogether(tasks);

        assertThat(outcomes).hasSize(20).allSatisfy(o -> assertThat(o.value()).isTrue());
    }

    @Test
    void runnerCapturesFailuresInsteadOfThrowingThem() throws Exception {
        List<Callable<String>> tasks = List.of(
                () -> "ok",
                () -> { throw new IllegalStateException("boom"); });

        var outcomes = ParallelRunner.runTogether(tasks);

        assertThat(outcomes.get(0).value()).isEqualTo("ok");
        assertThat(outcomes.get(1).error()).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }
}
