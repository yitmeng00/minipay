package com.minipay.paymenthub;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the ledger and the payment hub together (the hub depends on LedgerApi). */
@SpringBootApplication(scanBasePackages = {"com.minipay.ledger", "com.minipay.paymenthub"})
public class PaymentHubTestApplication {
}
