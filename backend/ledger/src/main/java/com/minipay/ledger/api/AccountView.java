package com.minipay.ledger.api;

import com.minipay.common.money.Money;

/** An account with its current balances. available = booked - reserved. */
public record AccountView(
        long id,
        String accountNumber,
        AccountType type,
        String currency,
        String ownerName,
        String counterpartyBic,
        AccountStatus status,
        boolean allowOverdraft,
        Money booked,
        Money reserved,
        Money available,
        long version) {
}
