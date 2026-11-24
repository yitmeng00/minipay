package com.minipay.bankapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * One bank in the MiniPay network. The same jar runs three times with different profiles:
 * {@code bank-a} (Kuala Lumpur, originator), {@code bank-b} (Frankfurt, correspondent) and
 * {@code bank-c} (London, beneficiary bank) — each with its own database, exactly like three real
 * banks that only talk to each other through messages.
 *
 * <p>Component scanning covers {@code com.minipay}, so each module contributes its own beans,
 * controllers and Flyway migrations. Modules only see each other's {@code api} packages (ArchUnit).
 */
@SpringBootApplication(scanBasePackages = "com.minipay")
@ConfigurationPropertiesScan
public class BankApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankApplication.class, args);
    }
}
