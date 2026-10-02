package dev.suvansh.ledger.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.suvansh.ledger.PostgresTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/v1/accounts/{id} against the full app and a real Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class GetAccountTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void returnsAccountDetailsAndCurrentBalance() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO accounts (id, type, name, balance_minor) VALUES (?, 'CUSTOMER', 'Jordan', 2500)")
                .param(id).update();

        mvc.perform(get("/api/v1/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.name").value("Jordan"))
                .andExpect(jsonPath("$.balance.value").value(2500))
                .andExpect(jsonPath("$.balance.currency").value("CAD"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void canReadTheTreasuryAccount() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + SystemAccounts.TREASURY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SYSTEM"))
                .andExpect(jsonPath("$.name").value("Treasury"));
    }

    @Test
    void accountOpenedByPostCanBeFetchedFromItsLocationHeader() throws Exception {
        String location = mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Round Trip\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Round Trip"))
                .andExpect(jsonPath("$.balance.value").value(0));
    }

    @Test
    void unknownAccountReturns404ProblemDetails() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/api/v1/accounts/" + id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:account-not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No account with id " + id + "."))
                .andExpect(jsonPath("$.instance").value("/api/v1/accounts/" + id));
    }

    @Test
    void malformedIdReturns400ProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
