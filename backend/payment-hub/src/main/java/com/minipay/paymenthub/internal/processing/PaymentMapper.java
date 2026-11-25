package com.minipay.paymenthub.internal.processing;

import com.minipay.common.money.Money;
import com.minipay.paymenthub.api.PaymentView;
import com.minipay.paymenthub.api.StateChangeView;
import com.minipay.paymenthub.internal.domain.Payment;
import com.minipay.paymenthub.internal.domain.PaymentStateHistory;
import java.math.BigDecimal;

public final class PaymentMapper {

    private PaymentMapper() {
    }

    public static PaymentView toView(Payment p) {
        return new PaymentView(p.id(), p.uetr().toString(), p.endToEndId(), p.instructionId(), p.direction(), p.status(),
                money(p.amount(), p.currency()), money(p.interbankSettlementAmount(), p.currency()),
                money(p.chargesAmount(), p.currency()), p.chargeBearer(),
                p.debtorName(), p.debtorAccount(), p.debtorAgentBic(),
                p.creditorName(), p.creditorAccount(), p.creditorAgentBic(), p.instructedAgentBic(),
                p.remittanceInfo(), p.requestedExecDate(), p.executionDate(), p.valueDate(), p.routingRuleId(),
                p.rejectionReason(), p.rejectionDetail(), p.reservationId(), p.createdAt(), p.updatedAt());
    }

    public static StateChangeView toView(PaymentStateHistory h) {
        return new StateChangeView(h.id(), h.fromStatus(), h.toStatus(), h.reasonCode(), h.detail(), h.actor(),
                h.occurredAt());
    }

    private static Money money(BigDecimal amount, String currency) {
        return amount == null ? null : Money.of(amount, currency);
    }
}
