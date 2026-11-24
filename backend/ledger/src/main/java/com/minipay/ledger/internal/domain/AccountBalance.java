package com.minipay.ledger.internal.domain;

import com.minipay.common.money.CurrencyUnits;
import com.minipay.common.money.Money;
import com.minipay.ledger.api.AccountStatus;
import com.minipay.ledger.api.AccountType;
import com.minipay.ledger.api.BalanceType;
import com.minipay.ledger.api.Direction;

/**
 * An account's balance row, read under a row lock ({@code SELECT ... FOR UPDATE}) and mutated in
 * memory before being written back in the same transaction.
 *
 * <h2>How a posting changes the balances</h2>
 * Balances are kept in the account's <em>normal</em> direction (see {@link AccountType}):
 * <ul>
 *   <li>BOOKED posting on the normal side → booked goes up; on the other side → booked goes down.</li>
 *   <li>RESERVED posting <em>against</em> the normal side → reserved goes up (an earmark for money
 *       that is about to leave); on the normal side → reserved goes down (the earmark is released).</li>
 * </ul>
 * Example: customer account (credit-normal). Reserve 100 = debit RESERVED 100 → reserved +100,
 * available −100, booked unchanged. Settle = release (credit RESERVED 100) + debit BOOKED 100 →
 * reserved back to 0, booked −100, available unchanged by the settlement step.
 */
public final class AccountBalance {

    private final long accountId;
    private final String accountNumber;
    private final AccountType type;
    private final AccountStatus status;
    private final boolean allowOverdraft;
    private long version;
    private Money booked;
    private Money reserved;

    public AccountBalance(long accountId, String accountNumber, AccountType type, AccountStatus status,
                          boolean allowOverdraft, Money booked, Money reserved, long version) {
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.type = type;
        this.status = status;
        this.allowOverdraft = allowOverdraft;
        this.booked = booked;
        this.reserved = reserved;
        this.version = version;
    }

    public static Money bookedDelta(AccountType type, Direction direction, Money amount) {
        return direction == type.normalSide() ? amount : amount.negate();
    }

    public static Money reservedDelta(AccountType type, Direction direction, Money amount) {
        return direction == type.normalSide() ? amount.negate() : amount;
    }

    public void apply(NewPosting posting) {
        if (posting.balanceType() == BalanceType.BOOKED) {
            booked = booked.plus(bookedDelta(type, posting.direction(), posting.amount()));
        } else {
            reserved = reserved.plus(reservedDelta(type, posting.direction(), posting.amount()));
        }
    }

    public Money available() {
        return booked.minus(reserved);
    }

    public long accountId() {
        return accountId;
    }

    public String accountNumber() {
        return accountNumber;
    }

    public AccountType type() {
        return type;
    }

    public AccountStatus status() {
        return status;
    }

    public boolean allowOverdraft() {
        return allowOverdraft;
    }

    public String currency() {
        return booked.currencyCode();
    }

    public Money booked() {
        return booked;
    }

    public Money reserved() {
        return reserved;
    }

    public long version() {
        return version;
    }

    /** Called after the row was written: the database bumped the version, so do we. */
    public void persisted() {
        version++;
    }

    public static Money zero(String currency) {
        return Money.zero(CurrencyUnits.currency(currency));
    }
}
