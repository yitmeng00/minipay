package com.minipay.refdata.internal.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reference data owns schema {@code refdata} and migrates it with its own Flyway (ADR-001 §2). */
@Configuration(proxyBeanMethods = false)
public class RefDataConfiguration {

    public static final String SCHEMA = "refdata";

    @Bean(initMethod = "migrate")
    public Flyway refdataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }
}
