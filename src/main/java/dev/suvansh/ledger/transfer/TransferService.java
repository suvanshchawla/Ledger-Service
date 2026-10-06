package dev.suvansh.ledger.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import dev.suvansh.ledger.account.AccountType;
import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.ProblemException;

/**
* Service for handling transfer operations.
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
    private final TransactionTemplate tx;
    
    TransferService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }
    private record LockedAccount(UUID id, AccountType type, long balanceMinor) {}
    
    
    public Transfer transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, Money amount) {
        
        String hash = requestHash(fromAccountId, toAccountId, amount);
        Transfer existing = findByKey(idempotencyKey, hash);
        if (existing != null) {
            return existing;
        }

        try{
            return tx.execute(txStatus -> book(idempotencyKey, hash, fromAccountId, toAccountId, amount));
        }catch (DuplicateKeyException e){
            Transfer winner = findByKey(idempotencyKey, hash);
            if (winner == null) throw e;
            return winner;
        }
        
    }
    
    private static String requestHash(UUID from, UUID to, Money amount) {
        String canonical = from + "|" + to + "|" + amount.minorUnits();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is guaranteed to exist on every JVM
        }
    }   
    
    private Transfer book(String idempotencyKey, String requestHash, UUID from, UUID to, Money amount){
        List<LockedAccount> locked = jdbc.sql("""
                    SELECT id, type, balance_minor FROM accounts WHERE id IN (:from, :to) ORDER BY id FOR UPDATE
                    """)
            .param("from", from)
            .param("to", to)
            .query((rs, rowNum) -> new LockedAccount(
                rs.getObject("id", UUID.class),
                AccountType.valueOf(rs.getString("type")),
                rs.getLong("balance_minor")))
                .list();
                
                // locked is sorted by id (for the lock order), so find the source row by id.
                LockedAccount source = locked.stream()
                .filter(a -> a.id().equals(from))
                .findFirst()
                .orElseThrow();
                
                boolean enough = source.type() == AccountType.SYSTEM || source.balanceMinor() >= amount.minorUnits();
                TransferStatus status = enough ? TransferStatus.COMMITTED : TransferStatus.REJECTED;
                
                // TODO 3: insert the transfers row (id, idempotency_key, request_hash, from, to, amount, status,
                //         reject_reason). request_hash is NOT NULL: SHA-256 of "from|to|amount" (minor units). reject_reason is NULL unless REJECTED.
                UUID transferId = UUID.randomUUID();
                
                
                
                
                String rejectReason = status == TransferStatus.REJECTED ? "Insufficient funds" : null;
                jdbc.sql("INSERT INTO transfers (id, idempotency_key, request_hash, from_account_id, to_account_id, amount_minor, status, reject_reason) VALUES (:id, :key, :hash, :from, :to, :amount, :status, :reason)")
                .param("id", transferId)
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("from", from)
                .param("to", to )
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
                    .param("accountId", from)
                    .param("amount", amount.negate().minorUnits())
                    .update();
                    
                    
                    jdbc.sql("INSERT INTO postings (journal_entry_id, account_id, amount_minor) VALUES (:entryId, :accountId, :amount)")
                    .param("entryId", entryID)
                    .param("accountId", to)
                    .param("amount", amount.minorUnits())
                    .update();
                    
                    jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :amount WHERE id = :accountId")
                    .param("amount", amount.negate().minorUnits())
                    .param("accountId", from)
                    .update();
                    
                    jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :amount WHERE id = :accountId")
                    .param("amount", amount.minorUnits())
                    .param("accountId", to)
                    .update();
                    
                }
                
                return new Transfer(transferId, status);
                
            }

        private Transfer findByKey(String idempotencyKey, String requestHash) {
            return jdbc.sql("""
                            SELECT id, status, request_hash FROM transfers WHERE idempotency_key = :key
                            """)
                    .param("key", idempotencyKey)
                    .query((rs, rowNum) -> {
                        if (!rs.getString("request_hash").equals(requestHash)) {
                            throw new ProblemException(
                                    HttpStatus.CONFLICT,
                                    "idempotency-key-reused",
                                    "Idempotency key reused",
                                    "This Idempotency-Key was already used with a different request.");
                        }
                        return new Transfer(
                                rs.getObject("id", UUID.class),
                                TransferStatus.valueOf(rs.getString("status")));
                    })
                    .optional()
                    .orElse(null);
        }

    
    }
        