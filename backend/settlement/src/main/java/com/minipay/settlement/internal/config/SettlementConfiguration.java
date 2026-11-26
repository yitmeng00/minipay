package com.minipay.settlement.internal.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Settlement owns schema {@code settlement} and migrates it with its own Flyway (ADR-001 §2). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementConfiguration {

    public static final String SCHEMA = "settlement";

    @Bean(initMethod = "migrate")
    public Flyway settlementFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }
}
