package com.minipay.network.internal.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** The network module owns schema {@code network} (the tracker) and its own Flyway (ADR-001 §2). */
@Configuration(proxyBeanMethods = false)
public class NetworkConfiguration {

    public static final String SCHEMA = "network";

    @Bean(initMethod = "migrate")
    public Flyway networkFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }

    /**
     * A message that fails for a technical reason (the database is briefly unavailable) is retried a few
     * times, one second apart, before it is logged and skipped; business refusals never get here.
     */
    @Bean
    DefaultErrorHandler networkErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, 5));
    }
}
