package dev.suvansh.ledger.transfer;

import java.util.UUID;

/** The stored outcome of a transfer request. Placeholder shape: can be reshaped as TransferService grows. */
public record Transfer(UUID id, TransferStatus status) {}
