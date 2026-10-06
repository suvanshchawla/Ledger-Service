package dev.suvansh.ledger.transfer;

import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.common.ProblemException;
import dev.suvansh.ledger.common.Problems;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final TransferService transfers;
    private final TransferRepository repository;

    TransferController(TransferService transfers, TransferRepository repository) {
        this.transfers = transfers;
        this.repository = repository;
    }

    /**
     * 201 for a booked transfer; 422 Problem Details (naming the stored transfer) for a rejected one.
     * A replay of the same key and body gets the same answer. A missing Idempotency-Key is a 428,
     * handled by ProblemDetailsHandler.
     */
    @PostMapping
    ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                             @Valid @RequestBody CreateTransferRequest request) {
        requireUsableKey(idempotencyKey);

        Transfer transfer = transfers.transfer(idempotencyKey, request.fromAccountId(), request.toAccountId(),
                Money.ofMinorUnits(request.amount().value()));
        // Answer from the stored row, so a replay is byte-for-byte the original response.
        TransferResponse stored = find(transfer.id());

        if (stored.status() == TransferStatus.REJECTED) {
            ProblemDetail problem = Problems.of(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds",
                    "Insufficient funds", "The source account does not have enough funds for this transfer.");
            problem.setProperty("transferId", stored.id());
            return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(stored.id()).toUri();
        return ResponseEntity.created(location).body(stored);
    }

    @GetMapping("/{id}")
    TransferResponse get(@PathVariable UUID id) {
        return find(id);
    }

    private TransferResponse find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
    }

    private static void requireUsableKey(String key) {
        if (key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "invalid-idempotency-key",
                    "Invalid Idempotency-Key header",
                    "The Idempotency-Key header must not be blank and at most "
                            + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
        }
    }
}
