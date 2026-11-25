package com.minipay.messaging.internal.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Messaging owns schema {@code messaging} and migrates it with its own Flyway (ADR-001 §2). */
@Configuration(proxyBeanMethods = false)
public class MessagingConfiguration {

    public static final String SCHEMA = "messaging";

    @Bean(initMethod = "migrate")
    public Flyway messagingFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }
}
