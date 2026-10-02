package dev.suvansh.ledger.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/accounts}. There is deliberately no type or balance field: every
 * account opened through the API is a CUSTOMER account that starts at zero.
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String name,
        @Pattern(regexp = "CAD", message = "only CAD is supported") String currency) {

    static final String DEFAULT_CURRENCY = "CAD";

    String currencyOrDefault() {
        return currency == null ? DEFAULT_CURRENCY : currency;
    }
}
