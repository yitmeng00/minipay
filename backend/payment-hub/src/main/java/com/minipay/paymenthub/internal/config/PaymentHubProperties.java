package com.minipay.paymenthub.internal.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param outwardFees    our flat fee per currency for an outgoing payment (e.g. EUR → 20.00).
 *                       A simplification: real tariffs depend on customer, channel and amount, and
 *                       would live in reference data.
 * @param recoveryStaleAfter a payment untouched this long in a pre-SENT state is considered stuck
 *                       (its processing thread died) and is resumed by the sweeper
 */
@ConfigurationProperties("minipay.payments")
public record PaymentHubProperties(Map<String, BigDecimal> outwardFees, Duration recoveryStaleAfter) {

    public PaymentHubProperties {
        outwardFees = outwardFees == null ? Map.of() : Map.copyOf(outwardFees);
        recoveryStaleAfter = recoveryStaleAfter == null ? Duration.ofSeconds(30) : recoveryStaleAfter;
    }
}
