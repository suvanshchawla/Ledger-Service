package dev.suvansh.ledger.account;

import dev.suvansh.ledger.common.Money;
import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, AccountType type, String name, String currency, Money balance, Instant createdAt) {}
