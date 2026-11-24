package com.minipay.ledger.api;

import com.minipay.common.money.Money;

public record PostingView(
        long id,
        long accountId,
        String accountNumber,
        Direction direction,
        Money amount,
        BalanceType balanceType) {
}
