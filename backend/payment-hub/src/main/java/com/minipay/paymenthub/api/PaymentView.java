package com.minipay.paymenthub.api;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentView(
        UUID id,
        String uetr,
        String endToEndId,
        String instructionId,
        PaymentDirection direction,
        PaymentStatus status,
        Money amount,
        Money interbankSettlementAmount,
        Money chargesAmount,
        ChargeBearer chargeBearer,
        String debtorName,
        String debtorAccount,
        String debtorAgentBic,
        String creditorName,
        String creditorAccount,
        String creditorAgentBic,
        String instructedAgentBic,
        String remittanceInformation,
        LocalDate requestedExecutionDate,
        LocalDate executionDate,
        LocalDate valueDate,
        String routingRuleId,
        String rejectionReason,
        String rejectionDetail,
        UUID reservationId,
        Instant createdAt,
        Instant updatedAt) {
}
