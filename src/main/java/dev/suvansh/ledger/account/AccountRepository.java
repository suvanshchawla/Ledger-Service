package dev.suvansh.ledger.account;

import dev.suvansh.ledger.common.Money;
import java.time.OffsetDateTime;
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
                .query((rs, rowNum) -> new Account(
                        rs.getObject("id", UUID.class),
                        AccountType.valueOf(rs.getString("type")),
                        rs.getString("name"),
                        rs.getString("currency"),
                        Money.ofMinorUnits(rs.getLong("balance_minor")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .single();
    }
}
