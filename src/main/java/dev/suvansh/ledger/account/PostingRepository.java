package dev.suvansh.ledger.account;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.MoneyDto;

/** Read side for an account's postings, returning the API representation directly. */
@Repository
class PostingRepository {

    private static final String SELECT_POSTINGS = """
            SELECT p.id, p.created_at, p.amount_minor, je.transfer_id,
                   c.id AS counterparty_id, c.name AS counterparty_name
            FROM postings p
            JOIN journal_entries je ON je.id = p.journal_entry_id
            JOIN transfers t ON t.id = je.transfer_id
            JOIN accounts c ON c.id = CASE WHEN t.from_account_id = p.account_id
                                           THEN t.to_account_id ELSE t.from_account_id END
            WHERE p.account_id = :account
            """;

    // Same columns and direction as idx_postings_account_time, so Postgres can seek to the cursor.
    private static final String AFTER_CURSOR = " AND (p.created_at, p.id) < (:cursorCreatedAt, :cursorId)";

    private static final String NEWEST_FIRST = " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit";

    private final JdbcClient jdbc;

    PostingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Up to {@code limit} postings of {@code account}, newest first, strictly older than
     * {@code cursor}, or starting from the newest when it is null. {@code currency} is the account's.
     *
     * <p>The counterparty comes from the transfer (its other end), so there is one row per posting.
     */
    List<PostingItemResponse> findPage(UUID account, String currency, PostingCursor cursor, int limit) {
        String sql = SELECT_POSTINGS + (cursor == null ? "" : AFTER_CURSOR) + NEWEST_FIRST;

        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("account", account)
                .param("limit", limit);
        if (cursor != null) {
            statement = statement
                    .param("cursorCreatedAt", OffsetDateTime.ofInstant(cursor.createdAt(), ZoneOffset.UTC))
                    .param("cursorId", cursor.postingId());
        }

        return statement
                .query((rs, rowNum) -> new PostingItemResponse(
                        rs.getLong("id"),
                        rs.getObject("transfer_id", UUID.class),
                        MoneyDto.of(Money.ofMinorUnits(rs.getLong("amount_minor")), currency),
                        new PostingItemResponse.Counterparty(
                                rs.getObject("counterparty_id", UUID.class),
                                rs.getString("counterparty_name")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}