package com.minipay.ledger.api;

/**
 * Account types and their <b>normal balance side</b>.
 *
 * <p>In double-entry bookkeeping every account has a side on which it "grows":
 * <ul>
 *   <li>Assets grow with debits. A NOSTRO ("ours, with you") is money we hold at another bank:
 *       an asset. SUSPENSE accounts are treated as debit-normal technical accounts.</li>
 *   <li>Liabilities and income grow with credits. A CUSTOMER account is money we owe the customer;
 *       a VOSTRO ("yours, with us") is money we owe another bank; FEE_INCOME is revenue.</li>
 * </ul>
 * This is why "debit the customer" means the customer's balance goes <em>down</em> while
 * "debit our nostro" means our money at the correspondent goes <em>up</em>.
 */
public enum AccountType {
    CUSTOMER(Direction.C),
    NOSTRO(Direction.D),
    VOSTRO(Direction.C),
    SUSPENSE(Direction.D),
    FEE_INCOME(Direction.C);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    public Direction normalSide() {
        return normalSide;
    }
}
