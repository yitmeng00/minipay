package com.minipay.refdata;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/** Reference data on its own, against a real PostgreSQL (the EXCLUDE constraints need btree_gist). */
@SpringBootApplication
public class RefDataTestApplication {

    /** Tuesday 24 Nov 2026, 10:00 in Kuala Lumpur. */
    public static final Instant NOW = Instant.parse("2026-11-24T02:00:00Z");

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
