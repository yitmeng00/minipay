package com.minipay.paymenthub.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Search criteria for the payments list. Every field is optional.
 *
 * @param text matches UETR, end-to-end id, instruction id, debtor/creditor name or account
 */
public record PaymentQuery(
        Set<PaymentStatus> statuses,
        PaymentDirection direction,
        String currency,
        LocalDate valueDateFrom,
        LocalDate valueDateTo,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String text,
        int page,
        int size) {

    public PaymentQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > 250) {
            throw new IllegalArgumentException("size must be 1..250");
        }
    }
}
