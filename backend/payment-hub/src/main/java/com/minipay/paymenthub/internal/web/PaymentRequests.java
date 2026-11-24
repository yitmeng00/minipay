package com.minipay.paymenthub.internal.web;

import com.minipay.common.iso.ChargeBearer;
import com.minipay.common.money.Money;
import com.minipay.paymenthub.api.NetworkEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** HTTP bodies. Field lengths follow ISO 20022 (Max35Text, Max140Text, IBAN 34). */
final class PaymentRequests {

    private PaymentRequests() {
    }

    record CreatePaymentRequest(
            @NotBlank @Size(max = 34) String debtorAccount,
            @NotBlank @Size(max = 140) String creditorName,
            @NotBlank @Size(max = 42) String creditorAccount, // IBANs are often typed with spaces
            @NotBlank @Size(max = 11) String creditorAgentBic,
            @NotNull Money amount,
            @NotNull ChargeBearer chargeBearer,
            LocalDate requestedExecutionDate,
            @Size(max = 35) String endToEndId,
            @Size(max = 140) String remittanceInformation) {
    }

    record NetworkEventRequest(
            @NotNull NetworkEvent.Type type,
            @Size(min = 4, max = 4) String reasonCode,
            Money returnedAmount,
            @Size(max = 35) String reference) {
    }
}
