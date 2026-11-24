package com.minipay.paymenthub.internal.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the payment hub: its own Flyway on schema {@code payment} (ADR-001 §2), its JPA entities
 * and repositories, and scheduling for the outbox relay and the recovery sweeper.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(PaymentHubProperties.class)
@EntityScan(basePackages = "com.minipay.paymenthub.internal")
@EnableJpaRepositories(basePackages = "com.minipay.paymenthub.internal.persistence")
public class PaymentHubConfiguration {

    public static final String SCHEMA = "payment";

    @Bean(initMethod = "migrate")
    public Flyway paymentFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }

    /** Hibernate validates the mapping against the tables at startup, so the tables must exist first. */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnPaymentFlyway() {
        return new EntityManagerFactoryDependsOnPostProcessor("paymentFlyway");
    }
}
