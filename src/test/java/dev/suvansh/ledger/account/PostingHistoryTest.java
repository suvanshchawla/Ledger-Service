package dev.suvansh.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.suvansh.ledger.LedgerFixture;
import dev.suvansh.ledger.PostgresTestConfig;
import dev.suvansh.ledger.common.Money;
import dev.suvansh.ledger.transfer.Transfer;
import dev.suvansh.ledger.transfer.TransferService;
import dev.suvansh.ledger.transfer.TransferStatus;

/**
 * GET /api/v1/accounts/{id}/postings: an account's postings, newest first, keyset-paginated.
 *
 * <pre>
 * { "items": [ { "postingId": 42, "transferId": "...", "amount": {"value": -2500, "currency": "CAD"},
 *                "counterparty": {"accountId": "...", "name": "Sam"}, "createdAt": "..." } ],
 *   "nextCursor": "opaque string" | null }
 * </pre>
 * Query parameters: {@code limit} (default 20, 1 to 100) and {@code cursor} (the previous page's
 * {@code nextCursor}; opaque to clients, so these tests never look inside it).
 *
 * <p>A customer opened with a balance has one funding posting, whose counterparty is the fixture's
 * "Test funding" system account; it is the oldest item in that account's history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class PostingHistoryTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    TransferService transfers;

    // ---- shape and content ----

    @Test
    void accountWithNoPostingsHasAnEmptyPageAndNoCursor() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID account = ledger.openCustomer("Empty", 0);

        JsonNode page = page(account, Map.of());

        assertThat(page.get("items")).isEmpty();
        assertThat(page.has("nextCursor")).as("nextCursor is always present").isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void postingsAreNewestFirstWithSignedAmounts() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 100_000);
        UUID sam = ledger.openCustomer("Sam", 0);
        ledger.recordEntry(alex, sam, 100, 100);
        ledger.recordEntry(alex, sam, 200, 200);
        ledger.recordEntry(sam, alex, 50, 50);

        JsonNode page = page(alex, Map.of());

        assertThat(amounts(page)).as("newest first; negative is money leaving").containsExactly(50L, -200L, -100L, 100_000L);
        assertThat(page.get("items").get(0).get("amount").get("currency").asText()).isEqualTo("CAD");
    }

    @Test
    void eachItemNamesTheTransferAndTheCounterparty() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 100_000);
        UUID sam = ledger.openCustomer("Sam", 0);
        UUID transferId = ledger.recordEntry(alex, sam, 300, 300);

        JsonNode alexItem = page(alex, Map.of()).get("items").get(0);
        JsonNode samItem = page(sam, Map.of()).get("items").get(0);

        assertThat(alexItem.get("postingId").isIntegralNumber()).isTrue();
        assertThat(alexItem.get("transferId").asText()).isEqualTo(transferId.toString());
        assertThat(alexItem.get("amount").get("value").asLong()).isEqualTo(-300);
        assertThat(alexItem.get("counterparty").get("accountId").asText()).isEqualTo(sam.toString());
        assertThat(alexItem.get("counterparty").get("name").asText()).isEqualTo("Sam");
        assertThat(alexItem.get("createdAt").asText()).isNotBlank();

        assertThat(samItem.get("amount").get("value").asLong()).isEqualTo(300);
        assertThat(samItem.get("counterparty").get("accountId").asText()).isEqualTo(alex.toString());
        assertThat(samItem.get("counterparty").get("name").asText()).isEqualTo("Alex");
    }

    @Test
    void theFundingDepositShowsTheSystemAccountAsCounterparty() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 100_000);

        JsonNode only = page(alex, Map.of()).get("items").get(0);

        assertThat(only.get("amount").get("value").asLong()).isEqualTo(100_000);
        assertThat(only.get("counterparty").get("accountId").asText()).isEqualTo(ledger.fundingAccount().toString());
        assertThat(only.get("counterparty").get("name").asText()).isEqualTo("Test funding");
    }

    @Test
    void onlyThisAccountsPostingsAreListed() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 1_000);
        UUID sam = ledger.openCustomer("Sam", 1_000);
        UUID kim = ledger.openCustomer("Kim", 1_000);
        ledger.recordEntry(sam, kim, 100, 100);

        JsonNode page = page(alex, Map.of());

        assertThat(amounts(page)).as("only Alex's own funding posting").containsExactly(1_000L);
    }

    @Test
    void aRejectedTransferLeavesNoPosting() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 500);
        UUID sam = ledger.openCustomer("Sam", 0);

        Transfer rejected = transfers.transfer("key-" + UUID.randomUUID(), alex, sam, Money.ofMinorUnits(1_000));

        assertThat(rejected.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(amounts(page(alex, Map.of()))).containsExactly(500L);
        assertThat(amounts(page(sam, Map.of()))).isEmpty();
    }

    // ---- pagination ----

    @Test
    void defaultPageHoldsTwentyItemsAndPointsToTheNextPage() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 1_000_000);
        UUID sam = ledger.openCustomer("Sam", 0);
        for (int i = 1; i <= 24; i++) {
            ledger.recordEntry(alex, sam, i, i);
        }

        JsonNode first = page(alex, Map.of());

        assertThat(first.get("items")).as("25 postings exist; the default page is 20").hasSize(20);
        assertThat(first.get("nextCursor").isNull()).isFalse();
    }

    @Test
    void pagesWalkEveryPostingExactlyOnceInOrder() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 100_000);
        UUID sam = ledger.openCustomer("Sam", 0);
        for (int i = 1; i <= 6; i++) {
            ledger.recordEntry(alex, sam, i * 100, i * 100);
        }
        List<Long> everything = amounts(page(alex, Map.of("limit", "100"))); // 7 postings

        List<Long> walked = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("limit", "3");
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode page = page(alex, params);
            walked.addAll(amounts(page));
            pageSizes.add(page.get("items").size());
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null);

        assertThat(everything).hasSize(7);
        assertThat(walked).as("no gaps, no repeats, same order").isEqualTo(everything);
        assertThat(pageSizes).containsExactly(3, 3, 1);
    }

    @Test
    void whenTheLastPageIsExactlyFullThereIsNoExtraEmptyPage() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 100_000);
        UUID sam = ledger.openCustomer("Sam", 0);
        for (int i = 1; i <= 5; i++) {
            ledger.recordEntry(alex, sam, i, i);
        } // 6 postings in total, limit 3: two full pages and nothing after

        JsonNode first = page(alex, Map.of("limit", "3"));
        JsonNode second = page(alex, Map.of("limit", "3", "cursor", first.get("nextCursor").asText()));

        assertThat(second.get("items")).hasSize(3);
        assertThat(second.get("nextCursor").isNull()).as("nothing is left, so no cursor").isTrue();
    }

    @Test
    void newPostingsArrivingBetweenPagesDoNotShiftTheNextPage() throws Exception {
        LedgerFixture ledger = new LedgerFixture(jdbc);
        UUID alex = ledger.openCustomer("Alex", 100_000);
        UUID sam = ledger.openCustomer("Sam", 0);
        ledger.recordEntry(alex, sam, 100, 100);
        ledger.recordEntry(alex, sam, 200, 200);
        ledger.recordEntry(alex, sam, 300, 300);
        // history, newest first: -300, -200, -100, +100000

        JsonNode first = page(alex, Map.of("limit", "2"));
        ledger.recordEntry(alex, sam, 999, 999); // a newer posting appears while the client is paging
        JsonNode second = page(alex, Map.of("limit", "2", "cursor", first.get("nextCursor").asText()));

        assertThat(amounts(first)).containsExactly(-300L, -200L);
        assertThat(amounts(second)).as("continues where page one stopped: no repeat of -200, no -999").containsExactly(-100L, 100_000L);
    }

    // ---- bad requests ----

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "abc"})
    void limitOutsideOneToHundredIs400(String limit) throws Exception {
        UUID account = new LedgerFixture(jdbc).openCustomer("Alex", 100);

        mvc.perform(request(account, Map.of("limit", limit)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100"})
    void limitBoundsAreInclusive(String limit) throws Exception {
        UUID account = new LedgerFixture(jdbc).openCustomer("Alex", 100);

        mvc.perform(request(account, Map.of("limit", limit))).andExpect(status().isOk());
    }

    @Test
    void invalidCursorIs400() throws Exception {
        UUID account = new LedgerFixture(jdbc).openCustomer("Alex", 100);

        mvc.perform(request(account, Map.of("cursor", "this-is-not-a-cursor")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(request(UUID.randomUUID(), Map.of()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-not-found"));
    }

    @Test
    void malformedAccountIdIs400() throws Exception {
        mvc.perform(get("/api/v1/accounts/not-a-uuid/postings"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- helpers ----

    private MockHttpServletRequestBuilder request(UUID account, Map<String, String> params) {
        MockHttpServletRequestBuilder request = get("/api/v1/accounts/" + account + "/postings");
        params.forEach(request::param);
        return request;
    }

    private JsonNode page(UUID account, Map<String, String> params) throws Exception {
        String body = mvc.perform(request(account, params))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<Long> amounts(JsonNode page) {
        List<Long> amounts = new ArrayList<>();
        page.get("items").forEach(item -> amounts.add(item.get("amount").get("value").asLong()));
        return amounts;
    }
}
