package com.minipay.compliance.internal.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compliance owns schema {@code compliance} and migrates it with its own Flyway (ADR-001 §2). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComplianceProperties.class)
public class ComplianceConfiguration {

    public static final String SCHEMA = "compliance";

    @Bean(initMethod = "migrate")
    public Flyway complianceFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }
}
