package com.minipay.paymenthub;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the ledger, reference data, messaging and the payment hub together (the hub depends on all three APIs). */
@SpringBootApplication(scanBasePackages = {"com.minipay.ledger", "com.minipay.refdata", "com.minipay.messaging", "com.minipay.paymenthub"})
public class PaymentHubTestApplication {
}
