package dev.suvansh.ledger.transfer;

import dev.suvansh.ledger.common.ProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class TransferNotFoundException extends ProblemException {

    public TransferNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "transfer-not-found", "Transfer not found", "No transfer with id " + id + ".");
    }
}
