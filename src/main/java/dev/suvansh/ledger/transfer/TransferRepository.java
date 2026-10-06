package dev.suvansh.ledger.transfer;

import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.MoneyDto;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read side for transfers, returning the API representation directly. Booking is TransferService's job. */
@Repository
class TransferRepository {

    private final JdbcClient jdbc;

    TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The transfer's currency is its source account's: a transfer never crosses currencies. */
    Optional<TransferResponse> findById(UUID id) {
        return jdbc.sql("""
                        SELECT t.id, t.status, t.from_account_id, t.to_account_id, t.amount_minor,
                               t.reject_reason, t.created_at, a.currency
                        FROM transfers t JOIN accounts a ON a.id = t.from_account_id
                        WHERE t.id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new TransferResponse(
                        rs.getObject("id", UUID.class),
                        TransferStatus.valueOf(rs.getString("status")),
                        rs.getObject("from_account_id", UUID.class),
                        rs.getObject("to_account_id", UUID.class),
                        MoneyDto.of(Money.ofMinorUnits(rs.getLong("amount_minor")), rs.getString("currency")),
                        rs.getString("reject_reason"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
