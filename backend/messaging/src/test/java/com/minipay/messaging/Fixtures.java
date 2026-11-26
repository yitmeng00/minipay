package com.minipay.messaging;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.messaging.api.Charge;
import com.minipay.messaging.api.CreditTransfer;
import com.minipay.messaging.api.SettlementMethod;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** A realistic outgoing payment: Bank A pays Harlow &amp; Finch in London, serially via Bank B. */
public final class Fixtures {

    public static final String UETR = "eb6305c9-1f7f-49de-aed0-16487c27b42d";
    public static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-11-24T10:12:04.356+08:00");

    private Fixtures() {
    }

    public static CreditTransfer transfer() {
        return transfer(ChargeBearer.SHAR, "Harlow & Finch Engineering Ltd", "INV-HF-2026-1187 CNC spindle assemblies");
    }

    public static CreditTransfer transfer(ChargeBearer bearer, String creditorName, String remittance) {
        Money amount = Money.of("48250.00", "EUR");
        Money fee = Money.of("20.00", "EUR");
        Money interbank = bearer == ChargeBearer.CRED ? amount.minus(fee) : amount;
        return new CreditTransfer(UETR, "MNPA261124000912", "INV-HF-2026-1187", "MNPAMYKLXXX", "MNPBDEFFXXX",
                "MNPAMYKLXXX", "MNPCGB2LXXX", "Selangor Precision Components Sdn Bhd", "514087234412",
                creditorName, "GB96MNPC60161331926819", interbank, amount,
                bearer == ChargeBearer.CRED ? List.of(new Charge(fee, "MNPAMYKLXXX")) : List.of(), bearer,
                LocalDate.of(2026, 11, 24), SettlementMethod.INDA, null, List.of(), remittance);
    }
}
