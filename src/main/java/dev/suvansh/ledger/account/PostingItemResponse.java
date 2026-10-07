package dev.suvansh.ledger.account;

import java.time.Instant;
import java.util.UUID;

import dev.suvansh.ledger.common.MoneyDto;

/**
 * One line of an account's history. {@code amount} is signed from this account's point of view:
 * negative is money leaving, positive is money arriving. {@code counterparty} is the account on
 * the other side of the transfer.
 */
public record PostingItemResponse(long postingId, UUID transferId, MoneyDto amount, Counterparty counterparty, Instant createdAt) {

    public record Counterparty(UUID accountId, String name) {}
}
