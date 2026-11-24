package com.minipay.ledger.internal.domain;

import com.minipay.common.money.Money;
import com.minipay.ledger.api.BalanceType;
import com.minipay.ledger.api.Direction;
import java.util.Objects;

/** A posting about to be written. Amount is strictly positive; direction carries the sign. */
public record NewPosting(long accountId, Direction direction, Money amount, BalanceType balanceType) {

    public NewPosting {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceType, "balanceType");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Posting amount must be positive, got " + amount);
        }
    }

    public NewPosting mirrored() {
        return new NewPosting(accountId, direction.opposite(), amount, balanceType);
    }
}
