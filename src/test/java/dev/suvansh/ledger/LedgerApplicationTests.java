package dev.suvansh.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestConfig.class)
class LedgerApplicationTests {

    @Test
    void contextLoads() {
    }
}
