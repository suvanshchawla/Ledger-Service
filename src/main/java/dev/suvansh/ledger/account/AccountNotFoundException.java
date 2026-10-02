package dev.suvansh.ledger.account;

import dev.suvansh.ledger.common.ProblemException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Public because other packages (transfers) throw it too. Surfaces as a 404 Problem Details response. */
public class AccountNotFoundException extends ProblemException {

    public AccountNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "account-not-found", "Account not found", "No account with id " + id + ".");
    }
}
