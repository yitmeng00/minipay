package com.minipay.ledger.api;

import com.minipay.common.money.Money;
import java.util.Objects;

/**
 * One destination of a settled hold.
 *
 * <p>A payment's hold usually covers more than the amount sent: with charge bearer DEBT or SHAR the
 * debtor also pays our fee, so the hold is "amount + fee". At settlement that single hold is split:
 * the principal goes to the nostro/vostro (a SETTLE entry) and the fee to fee income (a FEE entry).
 *
 * @param amount     null = "whatever is left of the hold" (at most one leg may omit it)
 * @param entryType  SETTLE or FEE
 */
public record SettlementLeg(long creditAccountId, Money amount, EntryType entryType, String narrative) {

    public SettlementLeg {
        Objects.requireNonNull(entryType, "entryType");
        if (entryType != EntryType.SETTLE && entryType != EntryType.FEE) {
            throw new IllegalArgumentException("A settlement leg is SETTLE or FEE, not " + entryType);
        }
    }

    public static SettlementLeg remainderTo(long creditAccountId, String narrative) {
        return new SettlementLeg(creditAccountId, null, EntryType.SETTLE, narrative);
    }
}
