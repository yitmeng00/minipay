package com.minipay.paymenthub.internal.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import org.junit.jupiter.api.Test;

class ChargesTest {

    private static final Money AMOUNT = Money.of("48250.00", "EUR");
    private static final Money FEE = Money.of("20.00", "EUR");

    @Test
    void shar_and_debt_charge_our_fee_on_top() {
        for (ChargeBearer b : new ChargeBearer[] {ChargeBearer.SHAR, ChargeBearer.DEBT}) {
            Charges c = Charges.of(b, AMOUNT, FEE);
            assertThat(c.hold()).isEqualTo(Money.of("48270.00", "EUR"));
            assertThat(c.interbankSettlementAmount()).isEqualTo(AMOUNT);
        }
    }

    @Test
    void cred_deducts_our_fee_from_the_amount_sent() {
        Charges c = Charges.of(ChargeBearer.CRED, AMOUNT, FEE);
        assertThat(c.hold()).isEqualTo(AMOUNT);
        assertThat(c.interbankSettlementAmount()).isEqualTo(Money.of("48230.00", "EUR"));
    }
}
