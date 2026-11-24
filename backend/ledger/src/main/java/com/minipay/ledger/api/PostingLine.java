package com.minipay.ledger.api;

import com.minipay.common.money.Money;
import java.util.Objects;

/** One requested debit or credit line. The amount must be positive; the direction carries the sign. */
public record PostingLine(long accountId, Direction direction, Money amount) {

    public PostingLine {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
    }
}
