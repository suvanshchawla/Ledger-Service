package dev.suvansh.ledger.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.suvansh.ledger.common.MoneyDto;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored transfer as the API shows it: the POST response and GET are the same representation.
 * {@code rejectReason} appears only when REJECTED.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(UUID id, TransferStatus status, UUID fromAccountId, UUID toAccountId,
                               MoneyDto amount, String rejectReason, Instant createdAt) {}
