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
 * @param sentStuckAfter a SENT payment without an acknowledgement from the next bank for this long
 *                       is flagged on the dashboard for an operator to chase
 * @param processingFees our charge, per currency, for a payment another bank sends us: deducted from
 *                       the amount under SHAR and CRED, whether we credit our customer or pass the
 *                       payment on (under DEBT the debtor's bank pays all, so nothing is deducted)
 */
@ConfigurationProperties("minipay.payments")
public record PaymentHubProperties(Map<String, BigDecimal> outwardFees, Duration recoveryStaleAfter,
                                   Duration sentStuckAfter, Map<String, BigDecimal> processingFees) {

    public PaymentHubProperties {
        outwardFees = outwardFees == null ? Map.of() : Map.copyOf(outwardFees);
        processingFees = processingFees == null ? Map.of() : Map.copyOf(processingFees);
        recoveryStaleAfter = recoveryStaleAfter == null ? Duration.ofSeconds(30) : recoveryStaleAfter;
        sentStuckAfter = sentStuckAfter == null ? Duration.ofMinutes(30) : sentStuckAfter;
    }
}
