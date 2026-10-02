package dev.suvansh.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container for all integration tests.
 *
 * <p>Spring caches an application context between test classes that share the same
 * configuration, so every {@code @SpringBootTest} that imports this class reuses the same
 * context and therefore the same container, instead of starting a new one per class.
 * {@code @ServiceConnection} points {@code spring.datasource.*} at the container, so tests
 * never touch the docker-compose database. Tests share the database, so each should
 * create its own rows (random ids) rather than assume an empty table.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16");
    }
}
