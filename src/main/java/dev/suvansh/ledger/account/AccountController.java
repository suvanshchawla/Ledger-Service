package dev.suvansh.ledger.account;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    ResponseEntity<AccountResponse> open(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accounts.openCustomerAccount(request.name(), request.currencyOrDefault());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(account.id()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accounts.getAccount(id));
    }
}
