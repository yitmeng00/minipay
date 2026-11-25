package com.minipay.messaging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/** Messaging with the ledger it builds statements from, against a real PostgreSQL. */
@SpringBootApplication(scanBasePackages = {"com.minipay.ledger", "com.minipay.messaging"})
public class MessagingTestApplication {

    /** Tuesday 24 Nov 2026, 10:00 in Frankfurt. */
    public static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
