package com.minipay.paymenthub.internal.processing;

import com.minipay.paymenthub.internal.domain.Payment;

/**
 * Sanctions screening, as seen by the payment hub. M6 provides the real implementation (fuzzy match
 * against an OFAC SDN sample, 4-eyes release); until then {@link PassThroughScreening} is used.
 */
public interface ScreeningPort {

    record Result(boolean hit, String detail) {
    }

    Result screen(Payment payment);
}
