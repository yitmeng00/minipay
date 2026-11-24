package com.minipay.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL 16 — never H2. The ledger relies on Postgres features (deferred constraint
 * triggers, partitioning, row locks, generated columns) that an in-memory fake would not exercise.
 * As a Spring bean, the container lives as long as the cached application context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "max_connections=200", "-c", "timezone=UTC");
    }
}
