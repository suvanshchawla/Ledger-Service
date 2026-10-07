package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.suvansh.ledger.LedgerFixture;
import dev.suvansh.ledger.PostgresTestConfig;
import dev.suvansh.ledger.common.Money;

/** The funds rule: customers cannot overdraw (to the cent), SYSTEM accounts may go negative. */
@SpringBootTest
@Import(PostgresTestConfig.class)
class TransferFundsTest {

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcClient jdbc;

    private final String key = "key-" + UUID.randomUUID();

    @Test
    void systemAccountMayGoNegative() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID customer = ledger.openCustomer("Customer", 0);

        Transfer result = transfers.transfer(key, ledger.fundingAccount(), customer, Money.ofMinorUnits(5_000));

        assertThat(result.status()).isEqualTo(TransferStatus.COMMITTED);
        assertThat(ledger.balanceOf(ledger.fundingAccount())).isEqualTo(-5_000);
        assertThat(ledger.balanceOf(customer)).isEqualTo(5_000);
        ledger.assertInvariantsHold();
    }

    @Test
    void customerMayTransferExactlyTheirWholeBalance() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 1_000);
        UUID to = ledger.openCustomer("To", 0);

        Transfer result = transfers.transfer(key, from, to, Money.ofMinorUnits(1_000));

        assertThat(result.status()).isEqualTo(TransferStatus.COMMITTED);
        assertThat(ledger.balanceOf(from)).isZero();
        assertThat(ledger.balanceOf(to)).isEqualTo(1_000);
        ledger.assertInvariantsHold();
    }

    @Test
    void customerCannotTransferOneCentMoreThanTheirBalance() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 1_000);
        UUID to = ledger.openCustomer("To", 0);

        Transfer result = transfers.transfer(key, from, to, Money.ofMinorUnits(1_001));

        assertThat(result.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(ledger.transfersWithKey(key)).as("the rejection itself is stored").isEqualTo(1);
        assertThat(ledger.journalEntriesForKey(key)).isZero();
        assertThat(ledger.balanceOf(from)).isEqualTo(1_000);
        assertThat(ledger.balanceOf(to)).isZero();
        ledger.assertInvariantsHold();
    }
}
