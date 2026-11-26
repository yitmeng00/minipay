package com.minipay.recon.internal.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reconciliation owns schema {@code recon} and migrates it with its own Flyway (ADR-001 §2). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReconProperties.class)
public class ReconConfiguration {

    public static final String SCHEMA = "recon";

    @Bean(initMethod = "migrate")
    public Flyway reconFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }
}
