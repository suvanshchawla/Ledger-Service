package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds ledger state with raw SQL (never through TransferService, so a broken service cannot
 * break test setup) and checks the ledger's invariants with SQL.
 *
 * <p>Each fixture owns a private SYSTEM funding account, so tests never touch the shared Treasury
 * and cannot interfere with each other. Not thread-safe: use it from the test thread only.
 */
final class LedgerFixture {

    private final JdbcClient jdbc;
    private final UUID fundingAccount;
    private final List<UUID> accounts = new ArrayList<>();

    LedgerFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.fundingAccount = insertAccount("SYSTEM", "Test funding", "CAD");
        accounts.add(fundingAccount);
    }

    UUID fundingAccount() {
        return fundingAccount;
    }

    /** Opens a CUSTOMER account and gives it {@code initialBalance} via a proper, balanced journal entry. */
    UUID openCustomer(String name, long initialBalance) {
        return openCustomer(name, initialBalance, "CAD");
    }

    /** Like {@link #openCustomer(String, long)} but in another currency; funded from the same (CAD) funding account. */
    UUID openCustomer(String name, long initialBalance, String currency) {
        UUID id = insertAccount("CUSTOMER", name, currency);
        accounts.add(id);
        if (initialBalance > 0) {
            recordEntry(fundingAccount, id, initialBalance, initialBalance);
        }
        return id;
    }

    /**
     * Books one journal entry: {@code -debit} on {@code from}, {@code +credit} on {@code to}, with
     * the cached balances updated to match. Normally debit equals credit; tests pass different
     * values to build a deliberately broken entry.
     */
    void recordEntry(UUID from, UUID to, long debit, long credit) {
        UUID transferId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO transfers (id, idempotency_key, request_hash, from_account_id, to_account_id,
                                               amount_minor, status)
                        VALUES (:id, :key, 'fixture', :from, :to, :amount, 'COMMITTED')
                        """)
                .param("id", transferId).param("key", "fixture-" + transferId)
                .param("from", from).param("to", to).param("amount", Math.max(debit, credit)).update();
        jdbc.sql("INSERT INTO journal_entries (id, transfer_id) VALUES (?, ?)")
                .param(entryId).param(transferId).update();
        insertPosting(entryId, from, -debit);
        insertPosting(entryId, to, credit);
        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?").param(-debit).param(from).update();
        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?").param(credit).param(to).update();
    }

    long balanceOf(UUID account) {
        return jdbc.sql("SELECT balance_minor FROM accounts WHERE id = ?").param(account).query(Long.class).single();
    }

    long totalBalanceOf(List<UUID> ids) {
        return jdbc.sql("SELECT COALESCE(SUM(balance_minor), 0)::bigint FROM accounts WHERE id IN (:ids)")
                .param("ids", ids).query(Long.class).single();
    }

    long transfersWithKey(String key) {
        return jdbc.sql("SELECT count(*) FROM transfers WHERE idempotency_key = ?").param(key)
                .query(Long.class).single();
    }

    long journalEntriesForKey(String key) {
        return jdbc.sql("""
                        SELECT count(*) FROM journal_entries je JOIN transfers t ON t.id = je.transfer_id
                        WHERE t.idempotency_key = ?
                        """)
                .param(key).query(Long.class).single();
    }

    long postingsForKey(String key) {
        return jdbc.sql("""
                        SELECT count(*) FROM postings p
                        JOIN journal_entries je ON je.id = p.journal_entry_id
                        JOIN transfers t ON t.id = je.transfer_id
                        WHERE t.idempotency_key = ?
                        """)
                .param(key).query(Long.class).single();
    }

    /** Journal entries whose transfer ran between two of the given accounts. */
    long journalEntriesAmong(List<UUID> ids) {
        return jdbc.sql("""
                        SELECT count(*) FROM journal_entries je JOIN transfers t ON t.id = je.transfer_id
                        WHERE t.from_account_id IN (:ids) AND t.to_account_id IN (:ids)
                        """)
                .param("ids", ids).query(Long.class).single();
    }

    /** Asserts the ledger invariants over every account this fixture created. */
    void assertInvariantsHold() {
        assertThat(jdbc.sql("""
                        SELECT journal_entry_id FROM postings
                        WHERE journal_entry_id IN (SELECT journal_entry_id FROM postings WHERE account_id IN (:ids))
                        GROUP BY journal_entry_id HAVING SUM(amount_minor) <> 0
                        """)
                .param("ids", accounts).query(UUID.class).list())
                .as("every journal entry sums to zero (entries that do not)").isEmpty();

        assertThat(jdbc.sql("""
                        SELECT a.id FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
                        WHERE a.id IN (:ids)
                        GROUP BY a.id, a.balance_minor
                        HAVING a.balance_minor <> COALESCE(SUM(p.amount_minor), 0)
                        """)
                .param("ids", accounts).query(UUID.class).list())
                .as("cached balance equals the sum of postings (accounts that differ)").isEmpty();

        assertThat(jdbc.sql("SELECT id FROM accounts WHERE id IN (:ids) AND type = 'CUSTOMER' AND balance_minor < 0")
                .param("ids", accounts).query(UUID.class).list())
                .as("no customer balance is negative (accounts that are)").isEmpty();

        assertThat(totalBalanceOf(accounts))
                .as("money is conserved: balances of all accounts, funding account included, sum to zero")
                .isZero();
    }

    private UUID insertAccount(String type, String name, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, type, name, currency) VALUES (?, ?, ?, ?)")
                .param(id).param(type).param(name).param(currency).update();
        return id;
    }

    private void insertPosting(UUID entryId, UUID account, long amount) {
        jdbc.sql("INSERT INTO postings (journal_entry_id, account_id, amount_minor) VALUES (?, ?, ?)")
                .param(entryId).param(account).param(amount).update();
    }
}
