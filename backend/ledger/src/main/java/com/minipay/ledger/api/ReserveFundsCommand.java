package com.minipay.ledger.api;

import com.minipay.common.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Place a hold on a customer's funds (payment state FUNDS_RESERVED).
 *
 * @param paymentId optional link to the payment hub's payment
 * @param reason    free text shown on the hold, e.g. "pacs.008 to MNPBDEFFXXX"
 */
public record ReserveFundsCommand(
        String idempotencyKey,
        long accountId,
        Money amount,
        UUID paymentId,
        String reason) {

    public ReserveFundsCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(amount, "amount");
    }
}
