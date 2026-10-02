package dev.suvansh.ledger.account;

import dev.suvansh.ledger.common.MoneyDto;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, AccountType type, String name, MoneyDto balance, Instant createdAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(account.id(), account.type(), account.name(),
                MoneyDto.of(account.balance(), account.currency()), account.createdAt());
    }
}
