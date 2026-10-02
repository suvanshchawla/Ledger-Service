package dev.suvansh.ledger.common;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web-layer test: only MVC components load (no database, no Flyway), so it runs in
 * about a second. The controller below exists only to trigger each error path.
 */
@WebMvcTest(ProblemDetailsHandlerTest.ThrowingController.class)
@Import(ProblemDetailsHandlerTest.ThrowingController.class)
class ProblemDetailsHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:validation-failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/test/validate"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "amount")));
    }

    @Test
    void malformedJsonReturns400ProblemDetails() throws Exception {
        mvc.perform(post("/test/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingIdempotencyKeyReturns428() throws Exception {
        mvc.perform(post("/test/idempotent"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:idempotency-key-required"))
                .andExpect(jsonPath("$.detail", containsString("Idempotency-Key")));
    }

    @Test
    void presentIdempotencyKeyIsAccepted() throws Exception {
        mvc.perform(post("/test/idempotent").header("Idempotency-Key", "abc"))
                .andExpect(status().isOk());
    }

    @Test
    void otherMissingHeaderStaysA400() throws Exception {
        mvc.perform(post("/test/other-header"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 422})
    void problemExceptionKeepsItsStatusAndDescribesItself(int code) throws Exception {
        mvc.perform(get("/test/problem/" + code))
                .andExpect(status().is(code))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(code))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:test-problem"))
                .andExpect(jsonPath("$.title").value("Test problem"))
                .andExpect(jsonPath("$.detail").value("Specific detail"))
                .andExpect(jsonPath("$.instance").value("/test/problem/" + code));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingInternals() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledger:problem:internal-error"))
                .andExpect(content().string(not(containsString("secret internal detail"))));
    }

    @RestController
    static class ThrowingController {

        record Body(@NotBlank String name, @Positive long amount) {}

        @PostMapping("/test/validate")
        void validate(@Valid @RequestBody Body body) {}

        @PostMapping("/test/idempotent")
        void idempotent(@RequestHeader("Idempotency-Key") String key) {}

        @PostMapping("/test/other-header")
        void otherHeader(@RequestHeader("X-Other") String other) {}

        @GetMapping("/test/problem/{status}")
        void problem(@PathVariable int status) {
            throw new ProblemException(HttpStatus.valueOf(status), "test-problem", "Test problem", "Specific detail");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
