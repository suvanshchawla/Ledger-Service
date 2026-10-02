package dev.suvansh.ledger.account;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;

    AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Opens a CUSTOMER account with a zero balance. SYSTEM accounts are deliberately not
     * creatable here: until there is authentication, an API that could mint one would be an API
     * that could mint money.
     */
    public Account openCustomerAccount(String name, String currency) {
        return accounts.insert(UUID.randomUUID(), AccountType.CUSTOMER, name.strip(), currency);
    }

    public Account getAccount(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
