package com.minipay.bankapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.LedgerApi;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** Boots Bank A as it runs locally (profile bank-a), against a throw-away PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("bank-a")
@Import(BankAppIT.Postgres.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // seed assertions first, then a payment moves money
class BankAppIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka:4.0.0");
        }
    }

    @Autowired
    LedgerApi ledger;

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationRunner ledgerSeeder;

    @Test
    void seeds_bank_a_books_that_balance() {
        assertThat(ledger.findAccountByNumber("514087234412")).get()
                .satisfies(a -> assertThat(a.booked()).isEqualTo(Money.of("1252580.18", "EUR")));
        // The nostro is the sum of the EUR deposits it backs — and equals Bank B's vostro for us.
        assertThat(ledger.findAccountByNumber("1101-EUR-MNPB")).get()
                .satisfies(a -> assertThat(a.booked()).isEqualTo(Money.of("2899613.17", "EUR")));
        assertThat(ledger.listAccounts(AccountType.VOSTRO)).hasSize(2);
        assertThat(ledger.checkIntegrity().ok()).isTrue();
    }

    @Test
    void seeding_again_is_a_no_op() throws Exception {
        long before = ledger.checkIntegrity().journalEntries();
        ledgerSeeder.run(new DefaultApplicationArguments());
        assertThat(ledger.checkIntegrity().journalEntries()).isEqualTo(before);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void a_payment_from_bank_a_goes_out_through_the_seeded_nostro() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/payments")
                        .header("Idempotency-Key", "bank-app-it-1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"debtorAccount": "514087234412", "creditorName": "Harlow & Finch Engineering Ltd",
                                 "creditorAccount": "GB67MNPC40051271520093", "creditorAgentBic": "MNPCGB2LXXX",
                                 "amount": {"amount": "48250.00", "currency": "EUR"}, "chargeBearer": "SHAR"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.instructedAgentBic").value("MNPBDEFFXXX"))
                .andExpect(jsonPath("$.chargesAmount.amount").value("20.00"));
        assertThat(ledger.checkIntegrity().ok()).isTrue();
    }

    @Test
    void bank_info_and_openapi_are_served() throws Exception {
        mvc.perform(get("/api/bank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bic").value("MNPAMYKLXXX"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Kuala_Lumpur"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/ledger/reservations']").exists())
                .andExpect(jsonPath("$.paths['/api/payments']").exists());
    }
}
