package com.minipay.paymenthub.internal.processing;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;

/**
 * Who pays our fee, in money terms.
 *
 * <pre>
 *                      hold on debtor     sent between banks      our fee
 *   DEBT / SHAR        amount + fee       amount                  charged on top to the debtor
 *   CRED               amount             amount − fee            deducted from what is sent
 * </pre>
 * (Under DEBT the debtor should also prepay the charges of banks further down the route; that needs
 * the route's tariffs and is out of scope until the network simulator.)
 */
public record Charges(Money fee, Money hold, Money interbankSettlementAmount) {

    public static Charges of(ChargeBearer bearer, Money amount, Money fee) {
        return switch (bearer) {
            case DEBT, SHAR -> new Charges(fee, amount.plus(fee), amount);
            case CRED -> new Charges(fee, amount, amount.minus(fee));
        };
    }
}
