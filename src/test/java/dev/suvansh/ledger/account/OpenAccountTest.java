package dev.suvansh.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.suvansh.ledger.PostgresTestConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** POST /api/v1/accounts against the full app and a real Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class OpenAccountTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void opensACustomerAccountWithZeroBalance() throws Exception {
        String body = open("{\"name\":\"Alex Chen\"}")
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.name").value("Alex Chen"))
                .andExpect(jsonPath("$.balance.value").value(0))
                .andExpect(jsonPath("$.balance.currency").value("CAD"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        Map<String, Object> row = jdbc.sql("SELECT type, name, currency, balance_minor FROM accounts WHERE id = ?")
                .param(UUID.fromString(id)).query().singleRow();
        assertThat(row).containsEntry("type", "CUSTOMER")
                .containsEntry("name", "Alex Chen")
                .containsEntry("currency", "CAD")
                .containsEntry("balance_minor", 0L);
    }

    @Test
    void locationHeaderPointsAtTheNewAccount() throws Exception {
        var response = open("{\"name\":\"Sam\"}").andExpect(status().isCreated())
                .andReturn().getResponse();
        String id = JsonPath.read(response.getContentAsString(), "$.id");

        assertThat(response.getHeader("Location")).isEqualTo("http://localhost/api/v1/accounts/" + id);
    }

    @Test
    void explicitCadIsAccepted() throws Exception {
        open("{\"name\":\"Alex\",\"currency\":\"CAD\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance.currency").value("CAD"));
    }

    @Test
    void surroundingWhitespaceInTheNameIsStripped() throws Exception {
        open("{\"name\":\"  Alex  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Alex"));
    }

    @Test
    void sameNameTwiceCreatesTwoAccounts() throws Exception {
        String first = open("{\"name\":\"Twin\"}").andReturn().getResponse().getContentAsString();
        String second = open("{\"name\":\"Twin\"}").andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(first, "$.id")).isNotEqualTo(JsonPath.read(second, "$.id"));
    }

    @Test
    void clientCannotChooseTypeOrBalance() throws Exception {
        // Unknown JSON properties are ignored (Spring Boot turns FAIL_ON_UNKNOWN_PROPERTIES off),
        // so these fields never reach the service.
        open("{\"name\":\"Sneaky\",\"type\":\"SYSTEM\",\"balance\":{\"value\":1000000,\"currency\":\"CAD\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.balance.value").value(0));
    }

    @Test
    void missingNameIsRejected() throws Exception {
        open("{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name")));
    }

    @Test
    void blankNameIsRejected() throws Exception {
        open("{\"name\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name")));
    }

    @Test
    void nameLongerThan100CharactersIsRejected() throws Exception {
        open("{\"name\":\"" + "x".repeat(101) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name")));
    }

    @Test
    void nonCadCurrencyIsRejected() throws Exception {
        open("{\"name\":\"Alex\",\"currency\":\"USD\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("currency")));
    }

    private ResultActions open(String json) throws Exception {
        return mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
