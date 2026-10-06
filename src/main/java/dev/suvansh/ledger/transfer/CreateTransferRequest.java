package dev.suvansh.ledger.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** Body of {@code POST /api/v1/transfers}. The Idempotency-Key travels in a header, not here. */
public record CreateTransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @Valid Amount amount) {

    /** {@code value} is in minor units (cents). Boxed so a missing value is reported, not read as 0. */
    public record Amount(
            @NotNull @Positive Long value,
            @NotNull @Pattern(regexp = "CAD", message = "only CAD is supported") String currency) {}
}
