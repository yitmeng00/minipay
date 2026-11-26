package com.minipay.paymenthub;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the ledger, reference data, messaging, compliance and the payment hub together (the hub depends on all four APIs). */
@SpringBootApplication(scanBasePackages = {"com.minipay.ledger", "com.minipay.refdata", "com.minipay.messaging", "com.minipay.compliance", "com.minipay.paymenthub"})
public class PaymentHubTestApplication {
}
