package dev.suvansh.ledger.transfer;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.suvansh.ledger.account.AccountType;
import dev.suvansh.ledger.common.Money;

/**
 * SKELETON. The real implementation (locking, idempotency, postings, outbox) is written by hand.
 *
 * <p>Contract the tests in this package assume:
 * <ul>
 *   <li>Insufficient funds is not an exception: the transfer is stored as REJECTED and returned.</li>
 *   <li>Replaying a key with the same request returns the originally stored {@link Transfer}
 *       (same id, same status) without moving money again.</li>
 *   <li>Replaying a key with a different request throws a {@code ProblemException} with status 409.</li>
 * </ul>
 */
@Service
public class TransferService {

    private final JdbcClient jdbc;

    TransferService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }
    private record SourceAccount(AccountType type, long balanceMinor) {}


    public Transfer transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, Money amount) {
        // Step 1 (naive version): no transaction, no locks, no idempotency. Make it work, then watch the
        // concurrency tests fail, then fix it.

        // TODO 1: read the source account (type, balance_minor) with a SELECT.
        SourceAccount source = jdbc.sql("SELECT type, balance_minor FROM accounts WHERE id = :id")
                .param("id", fromAccountId)
                .query((rs, rowNum) -> new SourceAccount(
                    AccountType.valueOf(rs.getString("type")), 
                    rs.getLong("balance_minor")))
                .single();

        // TODO 2: decide the outcome. A CUSTOMER source with balance < amount is REJECTED; otherwise COMMITTED.
        //         (SYSTEM accounts may go negative, so they are never rejected for funds.)
        boolean enough = source.type() == AccountType.SYSTEM || source.balanceMinor() >= amount.minorUnits();
        TransferStatus status = enough ? TransferStatus.COMMITTED : TransferStatus.REJECTED;

        // TODO 3: insert the transfers row (id, idempotency_key, request_hash, from, to, amount, status,
        //         reject_reason). request_hash is NOT NULL: use a placeholder until idempotency is built.
        UUID transferId = UUID.randomUUID();
        String rejectReason = status == TransferStatus.REJECTED ? "Insufficient funds" : null;
        jdbc.sql("INSERT INTO transfers (id, idempotency_key, request_hash, from_account_id, to_account_id, amount_minor, status, reject_reason) VALUES (:id, :key, 'placeholder', :from, :to, :amount, :status, :reason)")
                                               .param("id", transferId)
                                               .param("key", idempotencyKey)
                                               .param("from", fromAccountId)
                                               .param("to", toAccountId)
                                               .param("amount", amount.minorUnits())
                                               .param("status", status.name())
                                               .param("reason", rejectReason)
                                               .update();

        // TODO 4: if COMMITTED: insert a journal_entries row, then two postings (-amount on from, +amount on to),
        //         then UPDATE both accounts' balance_minor. See LedgerFixture.recordEntry in the test folder.
        if (status == TransferStatus.COMMITTED){
            UUID entryID = UUID.randomUUID();
            jdbc.sql("INSERT INTO journal_entries (id, transfer_id) VALUES (:entryId, :transferId)")
                .param("entryId", entryID)
                .param("transferId", transferId)
                .update();
            
            
            jdbc.sql("INSERT INTO postings (journal_entry_id, account_id, amount_minor) VALUES (:entryId, :accountId, :amount)")
                .param("entryId", entryID)
                .param("accountId", fromAccountId)
                .param("amount", amount.negate().minorUnits())
                .update();
            
            
            jdbc.sql("INSERT INTO postings (journal_entry_id, account_id, amount_minor) VALUES (:entryId, :accountId, :amount)")
                .param("entryId", entryID)
                .param("accountId", toAccountId)
                .param("amount", amount.minorUnits())
                .update();
            
            jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :amount WHERE id = :accountId")
                .param("amount", amount.negate().minorUnits())
                .param("accountId", fromAccountId)
                .update();
            
            jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :amount WHERE id = :accountId")
                .param("amount", amount.minorUnits())
                .param("accountId", toAccountId)
                .update();
            
        }

        Transfer transfer = new Transfer(transferId, status);

        return transfer;

        // TODO 5: return new Transfer(transferId, status).

        

        // Later steps, once the naive version fails the way you expect:
        // - wrap the work in a transaction (TransactionTemplate)
        // - lock both accounts with SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE
        // - idempotency: look up the key first, store a request hash, handle DuplicateKeyException
        // - outbox row in the same transaction

    }
}
