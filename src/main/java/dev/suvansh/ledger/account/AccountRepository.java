package dev.suvansh.ledger.account;

import dev.suvansh.ledger.common.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new account with a zero balance and returns it as stored. */
    Account insert(UUID id, AccountType type, String name, String currency) {
        return jdbc.sql("""
                        INSERT INTO accounts (id, type, name, currency)
                        VALUES (:id, :type, :name, :currency)
                        RETURNING id, type, name, currency, balance_minor, created_at
                        """)
                .param("id", id)
                .param("type", type.name())
                .param("name", name)
                .param("currency", currency)
                .query(AccountRepository::toAccount)
                .single();
    }

    Optional<Account> findById(UUID id) {
        return jdbc.sql("""
                        SELECT id, type, name, currency, balance_minor, created_at
                        FROM accounts WHERE id = :id
                        """)
                .param("id", id)
                .query(AccountRepository::toAccount)
                .optional();
    }

    private static Account toAccount(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                AccountType.valueOf(rs.getString("type")),
                rs.getString("name"),
                rs.getString("currency"),
                Money.ofMinorUnits(rs.getLong("balance_minor")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
