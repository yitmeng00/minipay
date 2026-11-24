package com.minipay.ledger.internal.config;

import com.minipay.common.json.MoneyJacksonModule;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the ledger module.
 *
 * <h2>One Flyway per module</h2>
 * Each module owns exactly one PostgreSQL schema and migrates it with its own Flyway instance, whose
 * history table lives inside that schema ({@code ledger.flyway_schema_history}). No module can run a
 * migration against another module's schema, and modules can evolve independently — the same shape
 * you would get if the module were later split into its own service. Spring Boot's single global
 * Flyway is switched off ({@code spring.flyway.enabled=false}).
 */
@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {

    public static final String SCHEMA = "ledger";

    @Bean(initMethod = "migrate")
    public Flyway ledgerFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration/" + SCHEMA)
                .load();
    }

    /** Serialises {@code Money} as {"amount":"12.50","currency":"EUR"} — amounts as strings, never floats. */
    @Bean
    @ConditionalOnMissingBean
    public MoneyJacksonModule moneyJacksonModule() {
        return new MoneyJacksonModule();
    }
}
