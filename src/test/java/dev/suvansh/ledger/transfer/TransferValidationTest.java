package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.suvansh.ledger.LedgerFixture;
import dev.suvansh.ledger.PostgresTestConfig;
import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.ProblemException;

/**
 * A transfer that can never be valid fails with a specific ProblemException (not a raw database
 * error) and leaves no trace: no transfer row, no postings, no balance change.
 */
@SpringBootTest
@Import(PostgresTestConfig.class)
class TransferValidationTest {

    @Autowired
    TransferService transfers;

    @Autowired
    JdbcClient jdbc;

    private final String key = "key-" + UUID.randomUUID();

    @Test
    void unknownSourceAccountIsA404() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID to = ledger.openCustomer("To", 0);

        assertFailsWith(HttpStatus.NOT_FOUND, "account-not-found",
                () -> transfers.transfer(key, UUID.randomUUID(), to, Money.ofMinorUnits(100)));

        assertNothingBooked(ledger);
    }

    @Test
    void unknownDestinationAccountIsA404() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 1_000);

        assertFailsWith(HttpStatus.NOT_FOUND, "account-not-found",
                () -> transfers.transfer(key, from, UUID.randomUUID(), Money.ofMinorUnits(100)));

        assertThat(ledger.balanceOf(from)).as("source untouched").isEqualTo(1_000);
        assertNothingBooked(ledger);
    }

    @Test
    void whenBothAccountsAreUnknownItIsStillA404() {
        LedgerFixture ledger = new LedgerFixture(jdbc);

        assertFailsWith(HttpStatus.NOT_FOUND, "account-not-found",
                () -> transfers.transfer(key, UUID.randomUUID(), UUID.randomUUID(), Money.ofMinorUnits(100)));

        assertNothingBooked(ledger);
    }

    @Test
    void transferToTheSameAccountIsA400() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID account = ledger.openCustomer("Solo", 1_000);

        assertFailsWith(HttpStatus.BAD_REQUEST, "same-account",
                () -> transfers.transfer(key, account, account, Money.ofMinorUnits(100)));

        assertThat(ledger.balanceOf(account)).isEqualTo(1_000);
        assertNothingBooked(ledger);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100, Long.MIN_VALUE})
    void zeroOrNegativeAmountIsA400(long minorUnits) {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 1_000);
        UUID to = ledger.openCustomer("To", 0);

        assertFailsWith(HttpStatus.BAD_REQUEST, "invalid-amount",
                () -> transfers.transfer(key, from, to, Money.ofMinorUnits(minorUnits)));

        assertThat(ledger.balanceOf(from)).isEqualTo(1_000);
        assertThat(ledger.balanceOf(to)).isZero();
        assertNothingBooked(ledger);
    }

    @Test
    void transferBetweenDifferentCurrenciesIsA422() {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID cad = ledger.openCustomer("Canadian", 1_000, "CAD");
        UUID usd = ledger.openCustomer("American", 0, "USD");

        assertFailsWith(HttpStatus.UNPROCESSABLE_ENTITY, "currency-mismatch",
                () -> transfers.transfer(key, cad, usd, Money.ofMinorUnits(100)));

        assertThat(ledger.balanceOf(cad)).isEqualTo(1_000);
        assertThat(ledger.balanceOf(usd)).isZero();
        assertNothingBooked(ledger);
    }

    private static void assertFailsWith(HttpStatus status, String slug, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getSlug()).isEqualTo(slug);
        });
    }

    private void assertNothingBooked(LedgerFixture ledger) {
        assertThat(ledger.transfersWithKey(key)).as("no transfer row stored").isZero();
        assertThat(ledger.journalEntriesForKey(key)).isZero();
        ledger.assertInvariantsHold();
    }
}
