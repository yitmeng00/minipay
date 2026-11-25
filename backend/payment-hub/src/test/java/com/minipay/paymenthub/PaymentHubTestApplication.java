package com.minipay.paymenthub;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the ledger, reference data and the payment hub together (the hub depends on both APIs). */
@SpringBootApplication(scanBasePackages = {"com.minipay.ledger", "com.minipay.refdata", "com.minipay.paymenthub"})
public class PaymentHubTestApplication {
}
