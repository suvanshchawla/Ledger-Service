package dev.suvansh.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import dev.suvansh.ledger.LedgerFixture;
import dev.suvansh.ledger.PostgresTestConfig;

/** POST /api/v1/transfers and GET /api/v1/transfers/{id}: the HTTP contract from the design doc. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class TransferEndpointTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private final String key = UUID.randomUUID().toString();

    // ---- POST: booked transfers ----

    @Test
    void bookedTransferReturns201WithLocationAndDetails() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);

        MockHttpServletResponse response = postTransfer(key, body(from, to, 2_500, "CAD"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.fromAccountId").value(from.toString()))
                .andExpect(jsonPath("$.toAccountId").value(to.toString()))
                .andExpect(jsonPath("$.amount.value").value(2_500))
                .andExpect(jsonPath("$.amount.currency").value("CAD"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse();

        String id = JsonPath.read(response.getContentAsString(), "$.id");
        assertThat(response.getHeader("Location")).isEqualTo("http://localhost/api/v1/transfers/" + id);
        assertThat(ledger.balanceOf(from)).isEqualTo(7_500);
        assertThat(ledger.balanceOf(to)).isEqualTo(2_500);
        ledger.assertInvariantsHold();
    }

    @Test
    void replayWithTheSameKeyAndBodyReturnsTheIdenticalResponseAndMovesMoneyOnce() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        String body = body(from, to, 2_500, "CAD");

        String first = postTransfer(key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = postTransfer(key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).as("same status, same transfer id, same everything").isEqualTo(first);
        assertThat(ledger.balanceOf(from)).isEqualTo(7_500);
        ledger.assertInvariantsHold();
    }

    @Test
    void sameKeyWithADifferentBodyIs409() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        postTransfer(key, body(from, to, 2_500, "CAD")).andExpect(status().isCreated());

        postTransfer(key, body(from, to, 3_000, "CAD"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:idempotency-key-reused"));

        assertThat(ledger.balanceOf(from)).isEqualTo(7_500);
    }

    // ---- POST: rejected transfers ----

    @Test
    void insufficientFundsIs422ProblemDetailsThatNamesTheStoredTransfer() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 500);
        UUID to = ledger.openCustomer("To", 0);
        String body = body(from, to, 1_000, "CAD");

        String problem = postTransfer(key, body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:insufficient-funds"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.transferId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String transferId = JsonPath.read(problem, "$.transferId");

        // The rejection is stored and can be fetched.
        mvc.perform(get("/api/v1/transfers/" + transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("Insufficient funds"));

        // A replay gives the same answer, naming the same transfer.
        postTransfer(key, body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.transferId").value(transferId));

        assertThat(ledger.balanceOf(from)).isEqualTo(500);
        assertThat(ledger.balanceOf(to)).isZero();
        ledger.assertInvariantsHold();
    }

    // ---- POST: the request itself is wrong ----

    @Test
    void missingIdempotencyKeyIs428() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), UUID.randomUUID(), 100, "CAD")))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:idempotency-key-required"));
    }

    @Test
    void blankIdempotencyKeyIs400() throws Exception {
        postTransfer(" ", body(UUID.randomUUID(), UUID.randomUUID(), 100, "CAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:invalid-idempotency-key"));
    }

    @Test
    void overlongIdempotencyKeyIs400() throws Exception {
        postTransfer("k".repeat(256), body(UUID.randomUUID(), UUID.randomUUID(), 100, "CAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:invalid-idempotency-key"));
    }

    @Test
    void emptyBodyListsEveryMissingField() throws Exception {
        postTransfer(key, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("fromAccountId", "toAccountId", "amount")));
    }

    @Test
    void nonPositiveAmountIs400() throws Exception {
        postTransfer(key, body(UUID.randomUUID(), UUID.randomUUID(), 0, "CAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("amount.value")));
    }

    @Test
    void nonCadCurrencyIs400() throws Exception {
        postTransfer(key, body(UUID.randomUUID(), UUID.randomUUID(), 100, "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("amount.currency")));
    }

    @Test
    void fractionalAmountIs400InsteadOfBeingTruncated() throws Exception {
        // Jackson would otherwise accept 12.5 for a long and silently book 12.
        postTransfer(key, "{\"fromAccountId\":\"" + UUID.randomUUID() + "\",\"toAccountId\":\""
                + UUID.randomUUID() + "\",\"amount\":{\"value\":12.5,\"currency\":\"CAD\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void malformedAccountIdIs400() throws Exception {
        postTransfer(key, "{\"fromAccountId\":\"nope\",\"toAccountId\":\"" + UUID.randomUUID()
                + "\",\"amount\":{\"value\":100,\"currency\":\"CAD\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- POST: the service's rules, as HTTP ----

    @Test
    void unknownAccountIs404() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID to = ledger.openCustomer("To", 0);

        postTransfer(key, body(UUID.randomUUID(), to, 100, "CAD"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-not-found"));
    }

    @Test
    void transferToTheSameAccountIs400() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID account = ledger.openCustomer("Solo", 1_000);

        postTransfer(key, body(account, account, 100, "CAD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:same-account"));
    }

    @Test
    void transferBetweenAccountsOfDifferentCurrenciesIs422() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID cad = ledger.openCustomer("Canadian", 1_000, "CAD");
        UUID usd = ledger.openCustomer("American", 0, "USD");

        postTransfer(key, body(cad, usd, 100, "CAD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:currency-mismatch"));
    }

    // ---- GET ----

    @Test
    void bookedTransferCanBeFetchedFromItsLocationHeader() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID from = ledger.openCustomer("From", 10_000);
        UUID to = ledger.openCustomer("To", 0);
        MockHttpServletResponse created = postTransfer(key, body(from, to, 2_500, "CAD"))
                .andExpect(status().isCreated()).andReturn().getResponse();

        String fetched = mvc.perform(get(created.getHeader("Location")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(fetched).as("GET returns the same representation as the POST").isEqualTo(created.getContentAsString());
    }

    @Test
    void unknownTransferIs404() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/api/v1/transfers/" + id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:transfer-not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/transfers/" + id));
    }

    @Test
    void malformedTransferIdIs400() throws Exception {
        mvc.perform(get("/api/v1/transfers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- helpers ----

    private ResultActions postTransfer(String idempotencyKey, String json) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return mvc.perform(request);
    }

    private static String body(UUID from, UUID to, long value, String currency) {
        return """
                {"fromAccountId":"%s","toAccountId":"%s","amount":{"value":%d,"currency":"%s"}}
                """.formatted(from, to, value, currency);
    }
}
