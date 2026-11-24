package com.minipay.ledger.api;

import java.util.Objects;

/**
 * Open an account. Not idempotency-keyed: the account number itself is the natural key and is unique.
 *
 * @param counterpartyBic required for NOSTRO/VOSTRO, the other bank in the relationship
 * @param allowOverdraft  whether available balance may go below zero (credit line / technical account)
 */
public record OpenAccountCommand(
        String accountNumber,
        AccountType type,
        String currency,
        String ownerName,
        String counterpartyBic,
        boolean allowOverdraft) {

    public OpenAccountCommand {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(ownerName, "ownerName");
    }
}
