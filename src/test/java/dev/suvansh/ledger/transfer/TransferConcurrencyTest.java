package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.suvansh.ledger.PostgresTestConfig;
import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.transfer.ParallelRunner.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proof of correctness under concurrency. Do not weaken these to make them pass: if one fails,
 * the locking is wrong.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
class TransferConcurrencyTest {

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcClient jdbc;

    @Test
    void thousandRandomTransfersAmongFiveAccountsKeepEveryInvariant() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        List<UUID> accounts = IntStream.range(0, 5)
                .mapToObj(i -> ledger.openCustomer("Account " + i, 10_000)).toList();
        Random random = new Random(42);
        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            int from = random.nextInt(accounts.size());
            int to = (from + 1 + random.nextInt(accounts.size() - 1)) % accounts.size(); // never equal to from
            Money amount = Money.ofMinorUnits(1 + random.nextInt(2_500));
            String key = UUID.randomUUID().toString();
            tasks.add(() -> transfers.transfer(key, accounts.get(from), accounts.get(to), amount));
        }

        List<Outcome<Transfer>> outcomes = ParallelRunner.runTogether(tasks);

        assertThat(outcomes.stream().filter(Outcome::failed).map(Outcome::error).toList())
                .as("no transfer may throw").isEmpty();
        long committed = count(outcomes, TransferStatus.COMMITTED);
        long rejected = count(outcomes, TransferStatus.REJECTED);
        assertThat(committed + rejected).as("every request got a final answer").isEqualTo(1_000);
        assertThat(ledger.journalEntriesAmong(accounts))
                .as("one journal entry per committed transfer, none for rejected").isEqualTo(committed);
        assertThat(ledger.totalBalanceOf(accounts))
                .as("transfers among these accounts neither create nor destroy money").isEqualTo(50_000);
        ledger.assertInvariantsHold();
    }

    @Test
    void hundredParallelWithdrawalsFromOneAccountCommitExactlyAsManyAsItCanAfford() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID source = ledger.openCustomer("Source", 1_000);
        UUID target = ledger.openCustomer("Target", 0);
        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String key = UUID.randomUUID().toString();
            tasks.add(() -> transfers.transfer(key, source, target, Money.ofMinorUnits(100)));
        }

        List<Outcome<Transfer>> outcomes = ParallelRunner.runTogether(tasks);

        assertThat(outcomes.stream().filter(Outcome::failed).map(Outcome::error).toList())
                .as("no transfer may throw").isEmpty();
        assertThat(count(outcomes, TransferStatus.COMMITTED)).as("1,000 buys exactly ten withdrawals of 100").isEqualTo(10);
        assertThat(count(outcomes, TransferStatus.REJECTED)).isEqualTo(90);
        assertThat(ledger.balanceOf(source)).isZero();
        assertThat(ledger.balanceOf(target)).isEqualTo(1_000);
        ledger.assertInvariantsHold();
    }

    @Test
    void opposingTransfersBetweenTwoAccountsDoNotDeadlock() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID a = ledger.openCustomer("A", 1_000_000);
        UUID b = ledger.openCustomer("B", 1_000_000);
        List<Callable<Transfer>> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String keyAtoB = UUID.randomUUID().toString();
            String keyBtoA = UUID.randomUUID().toString();
            tasks.add(() -> transfers.transfer(keyAtoB, a, b, Money.ofMinorUnits(100)));
            tasks.add(() -> transfers.transfer(keyBtoA, b, a, Money.ofMinorUnits(100)));
        }

        List<Outcome<Transfer>> outcomes = ParallelRunner.runTogether(tasks);

        assertThat(outcomes.stream().filter(Outcome::failed).map(Outcome::error).toList())
                .as("no transfer may throw (a deadlock victim would show up here)").isEmpty();
        assertThat(count(outcomes, TransferStatus.COMMITTED)).as("balances are ample, so all commit").isEqualTo(1_000);
        assertThat(ledger.balanceOf(a)).as("500 each way cancels out").isEqualTo(1_000_000);
        assertThat(ledger.balanceOf(b)).isEqualTo(1_000_000);
        ledger.assertInvariantsHold();
    }

    private static long count(List<Outcome<Transfer>> outcomes, TransferStatus status) {
        return outcomes.stream().filter(o -> o.value() != null && o.value().status() == status).count();
    }
}
