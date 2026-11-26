package com.minipay.paymenthub.api;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A customer credit transfer instruction from a channel (online banking, host-to-host file …) —
 * in ISO terms, one transaction of a pain.001.
 *
 * @param debtorAccount           our customer's account number
 * @param requestedExecutionDate  null = today's business date; a future date warehouses the payment
 * @param endToEndId              the debtor's own reference; null = "NOTPROVIDED"
 * @param serviceLevel            normal, urgent or instant (ADR-008); null = NORM
 */
public record CreatePaymentCommand(
        String idempotencyKey,
        String debtorAccount,
        String creditorName,
        String creditorAccount,
        String creditorAgentBic,
        Money amount,
        ChargeBearer chargeBearer,
        LocalDate requestedExecutionDate,
        String endToEndId,
        String remittanceInformation,
        ServiceLevel serviceLevel) {

    public CreatePaymentCommand(String idempotencyKey, String debtorAccount, String creditorName, String creditorAccount,
                                String creditorAgentBic, Money amount, ChargeBearer chargeBearer,
                                LocalDate requestedExecutionDate, String endToEndId, String remittanceInformation) {
        this(idempotencyKey, debtorAccount, creditorName, creditorAccount, creditorAgentBic, amount, chargeBearer,
                requestedExecutionDate, endToEndId, remittanceInformation, ServiceLevel.NORM);
    }

    public CreatePaymentCommand {
        serviceLevel = serviceLevel == null ? ServiceLevel.NORM : serviceLevel;
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(debtorAccount, "debtorAccount");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(chargeBearer, "chargeBearer");
    }
}
