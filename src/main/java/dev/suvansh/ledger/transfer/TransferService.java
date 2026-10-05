package dev.suvansh.ledger.transfer;

import dev.suvansh.ledger.common.Money;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * STUB. The real implementation (locking, idempotency, postings, outbox) is written by hand.
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

    public Transfer transfer(String idempotencyKey, UUID fromAccountId, UUID toAccountId, Money amount) {
        throw new UnsupportedOperationException("TransferService is not implemented yet");
    }
}
